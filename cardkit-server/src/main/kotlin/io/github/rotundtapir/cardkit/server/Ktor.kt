// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.server

import io.github.rotundtapir.cardkit.net.ClientMessage
import io.github.rotundtapir.cardkit.net.ClientMessageSerializer
import io.github.rotundtapir.cardkit.net.ErrorCode
import io.github.rotundtapir.cardkit.net.ErrorMessage
import io.github.rotundtapir.cardkit.net.Hello
import io.github.rotundtapir.cardkit.net.ServerMessage
import io.github.rotundtapir.cardkit.net.ServerMessageSerializer
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("transport")

/** WebSocket close code for a client that is too old to talk to this server. */
const val CLOSE_UPDATE_REQUIRED: Short = 4426

/**
 * Installs the WebSocket transport and HTTP endpoints for [server]. A game's `main()` hosts this in
 * an `embeddedServer(CIO, port = config.port) { gameServerModule(server, config) }`; tests host it
 * with `testApplication` and their own [GameServer]/[ServerConfig].
 *
 * Endpoints: `GET /health` (drain-aware, what the container healthcheck and the deploy poll read),
 * `GET /metrics` (Prometheus text; blocked at the proxy), `POST /admin/drain|/admin/undrain` (also
 * proxy-blocked), and `WS /ws` — the entire game protocol.
 */
fun <S : Any, A : Any, V : Any, C : Any> Application.gameServerModule(
    server: GameServer<S, A, V, C>,
    config: ServerConfig,
) {
    install(WebSockets) {
        pingPeriodMillis = PING_PERIOD_SECONDS * 1000
        timeoutMillis = PONG_TIMEOUT_SECONDS * 1000
        maxFrameSize = config.maxFrameBytes
    }
    if (config.trustProxy) install(XForwardedHeaders)

    monitor.subscribe(ApplicationStopPreparing) {
        // With durable persistence, shutdown() is a no-op per room (snapshots outlive the process
        // and clients reconnect); the flush pushes any still-queued snapshot writes to disk first.
        server.rooms.all().forEach { it.shutdown() }
        server.persistence.flushSync()
    }

    routing {
        get("/health") {
            // "game" identifies WHICH game answered. Two games share one host/proxy here, and a
            // test suite's "is anything listening on this port?" probe cannot otherwise tell a
            // stale or foreign server from its own — it proceeds and then fails at the handshake,
            // which reads as "my game is broken". Callers should match on this before trusting it.
            val body = """{"status":"ok","game":"${server.descriptor.metricsPrefix}",""" +
                """"rooms":${server.rooms.roomCount()},""" +
                """"activeGames":${server.rooms.activeGames()},"draining":${server.rooms.draining}}"""
            call.respondText(body, ContentType.Application.Json)
        }
        get("/metrics") {
            call.respondText(server.metrics.render(server.rooms.roomCount(), server.rooms.draining))
        }
        post("/admin/drain") {
            server.rooms.setDraining(true)
            call.respondText("draining")
        }
        post("/admin/undrain") {
            server.rooms.setDraining(false)
            call.respondText("serving")
        }
        webSocket("/ws") {
            handleSocket(server, config)
        }
    }
}

/** The per-connection lifecycle: handshake, then pump frames until the socket closes. */
private suspend fun <S : Any, A : Any, V : Any, C : Any> DefaultWebSocketServerSession.handleSocket(
    server: GameServer<S, A, V, C>,
    config: ServerConfig,
) {
    val origin = call.request.headers["Origin"]
    if (!config.originAllowed(origin)) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin not allowed"))
        return
    }
    val ip = call.request.origin.remoteHost
    if (!server.tryOpenConnection(ip)) {
        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many connections"))
        return
    }
    server.metrics.connectionOpened()
    try {
        val json = server.descriptor.wireJson
        val hello = readHello(json) ?: return
        when (val result = server.processHello(hello, ip)) {
            is GameServer.HelloResult.Rejected -> {
                sendMessage(json, result.response)
                close(CloseReason(CLOSE_UPDATE_REQUIRED, "update required"))
                return
            }
            is GameServer.HelloResult.Accepted -> runSession(server, config, hello, ip, result)
        }
    } catch (_: ClosedReceiveChannelException) {
        // The client vanished without a clean close frame — a phone backgrounding, a dropped network,
        // a closed tab. Routine churn, not a server fault: swallow it so it isn't logged as an ERROR
        // ("Websocket handler failed"). The finally below still runs the normal disconnect cleanup.
    } catch (e: IOException) {
        // Same routine churn surfaced differently: a ping timeout ("Ping timeout") or a reset socket
        // arrives as an IOException. Log at debug, never ERROR — an unreachable phone is not a fault.
        log.debug("socket closed abnormally: {}", e.message)
    } finally {
        server.closeConnection(ip)
        server.metrics.connectionClosed()
    }
}

private suspend fun <S : Any, A : Any, V : Any, C : Any> DefaultWebSocketServerSession.runSession(
    server: GameServer<S, A, V, C>,
    config: ServerConfig,
    hello: Hello,
    ip: String,
    accepted: GameServer.HelloResult.Accepted<S, A, V, C>,
) {
    val json = server.descriptor.wireJson
    val connection = PlayerConnection(
        id = server.nextConnectionId(),
        sessionToken = accepted.token,
        remoteIp = ip,
        platform = hello.platform,
        appVersion = hello.appVersion,
        buildFlavor = hello.buildFlavor,
        commit = hello.commit,
        requestClose = { launch { runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "evicted")) } } },
    )
    server.onConnected(connection, accepted.resumeRoom)
    sendMessage(json, accepted.welcome)
    val resumeRoom = accepted.resumeRoom
    val resumeSeat = accepted.resumeSeat
    if (resumeRoom != null && resumeSeat != null) {
        connection.roomId = resumeRoom.gameId
        resumeRoom.submit(RoomCommand.Reconnect(connection, resumeSeat))
    }
    val writer = launch {
        for (message in connection.outbound) sendMessage(json, message)
    }
    val limiter = RateLimiter(config.messageRatePerSecond, config.messageBurst, System::currentTimeMillis)
    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            pumpFrame(server, connection, limiter, frame.readText())
        }
    } finally {
        connection.connected = false
        connection.outbound.close()
        writer.cancel()
        server.onDisconnected(connection)
    }
}

private fun <S : Any, A : Any, V : Any, C : Any> pumpFrame(
    server: GameServer<S, A, V, C>,
    connection: PlayerConnection,
    limiter: RateLimiter,
    text: String,
) {
    if (!server.config.devMode && !limiter.tryAcquire()) {
        server.onRateLimited(connection)
        connection.enqueue(ErrorMessage(ErrorCode.RATE_LIMITED, "Slow down"))
        return
    }
    server.metrics.messageReceived()
    val message = runCatching {
        server.descriptor.wireJson.decodeFromString(ClientMessageSerializer, text)
    }.getOrNull()
    if (message == null) {
        // Log-only for now (per the v1 decision) — malformed frames don't drop the socket yet.
        server.onMalformed(connection)
        connection.enqueue(ErrorMessage(ErrorCode.MALFORMED, "Unrecognised message"))
        return
    }
    server.route(connection, message)
}

/** Read and decode the opening [Hello], bounded by a short timeout so silent sockets don't linger. */
private suspend fun DefaultWebSocketServerSession.readHello(json: Json): Hello? {
    val text = withTimeoutOrNull(HELLO_TIMEOUT_SECONDS.seconds) {
        (incoming.receive() as? Frame.Text)?.readText()
    } ?: return null
    return runCatching { json.decodeFromString(ClientMessageSerializer, text) }.getOrNull() as? Hello
}

private suspend fun DefaultWebSocketServerSession.sendMessage(json: Json, message: ServerMessage) {
    send(Frame.Text(json.encodeToString(ServerMessageSerializer, message)))
}

private const val PING_PERIOD_SECONDS = 20L
private const val PONG_TIMEOUT_SECONDS = 40L
private const val HELLO_TIMEOUT_SECONDS = 15L
