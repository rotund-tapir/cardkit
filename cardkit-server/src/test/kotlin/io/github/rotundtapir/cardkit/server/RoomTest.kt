// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.server

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.net.DisbandReason
import io.github.rotundtapir.cardkit.net.ErrorCode
import io.github.rotundtapir.cardkit.net.ErrorMessage
import io.github.rotundtapir.cardkit.net.GameOver
import io.github.rotundtapir.cardkit.net.JoinLobby
import io.github.rotundtapir.cardkit.net.LobbyDisbanded
import io.github.rotundtapir.cardkit.net.LobbyState
import io.github.rotundtapir.cardkit.net.OccupancyStatus
import io.github.rotundtapir.cardkit.net.Platform
import io.github.rotundtapir.cardkit.net.RoomPhase
import io.github.rotundtapir.cardkit.net.SeatStatus
import io.github.rotundtapir.cardkit.net.ServerMessage
import io.github.rotundtapir.cardkit.net.SetReady
import io.github.rotundtapir.cardkit.net.StartGame
import io.github.rotundtapir.cardkit.net.SubmitAction
import io.github.rotundtapir.cardkit.net.ViewUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * The room actor driven end to end through [GameServer], using in-memory [PlayerConnection]s instead
 * of sockets — the whole point of keeping the room transport-agnostic. Covers a full game to its
 * terminal state, action validation, bot substitution with seat reclaim, restart restore from a
 * snapshot, config refusal and idle disband.
 *
 * Real time (not virtual): the room's idle ticker is an unbounded `while (isActive) delay(...)` loop,
 * which a virtual-time scheduler would spin on forever. Bots answer instantly, so a toy game finishes
 * in milliseconds and every wait below is a generous timeout rather than a sleep.
 */
class RoomTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun server(config: ServerConfig = devConfig()): GameServer<ToyState, ToyAction, ToyView, ToyConfig> =
        serverWithScope(config).first

    /** A server plus the scope that owns its coroutines, so a test can end that "process". */
    private fun serverWithScope(
        config: ServerConfig = devConfig(),
    ): Pair<GameServer<ToyState, ToyAction, ToyView, ToyConfig>, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return GameServer(config, scope, ToyDescriptor) to scope
    }

    private fun devConfig(
        dataDir: String? = null,
        idleDisbandMillisOverride: Long? = null,
        lobbyDisconnectGraceMillis: Long = 60_000,
        // 0 = the pre-grace instant substitution, which most tests here rely on for promptness.
        gameDisconnectGraceMillis: Long = 0,
    ) = ServerConfig(
        devMode = true,
        dataDir = dataDir,
        idleDisbandMillisOverride = idleDisbandMillisOverride,
        lobbyDisconnectGraceMillis = lobbyDisconnectGraceMillis,
        gameDisconnectGraceMillis = gameDisconnectGraceMillis,
    )

    private var nextId = 0L

    private fun connection(token: String) = PlayerConnection(
        id = ++nextId,
        sessionToken = token,
        remoteIp = "1.2.3.4",
        platform = Platform.WEB,
        appVersion = "1.0.0",
        requestClose = {},
    )

    /** Wait for the next message of type [T], skipping anything else. */
    private suspend inline fun <reified T : ServerMessage> PlayerConnection.await(): T =
        withTimeout(AWAIT_MILLIS) {
            var next = outbound.receive()
            while (next !is T) next = outbound.receive()
            next
        }

    /** Create a lobby, returning the seated creator connection and its lobby snapshot. */
    private suspend fun GameServer<ToyState, ToyAction, ToyView, ToyConfig>.createLobby(
        token: String = "creator",
        name: String = "Alice",
        target: Int = TOY_TARGET,
        seed: Long? = 7L,
    ): Pair<PlayerConnection, LobbyState<ToyConfig>> {
        val conn = connection(token)
        route(conn, ToyCreateLobby(displayName = name, target = target, seed = seed))
        return conn to conn.await()
    }

    @Test
    fun `a lobby fills empty seats with bots and plays a full game to its terminal state`() = runBlocking {
        val server = server()
        val (host, lobby) = server.createLobby()
        assertEquals(RoomPhase.LOBBY, lobby.phase)
        assertEquals(TOY_SEATS, lobby.seats.size)

        server.route(host, StartGame)
        val playing = awaitLobbyPhase(host, RoomPhase.PLAYING)
        // The one empty seat became a permanent bot, labelled as such.
        val bots = playing.seats.filter { it.isBot }
        assertEquals(1, bots.size)
        assertTrue(bots.single().name.endsWith("(bot)"), "bot seat should be labelled: ${bots.single().name}")

        val result = playToCompletion(server, host)
        assertEquals(TOY_SEATS, result.scores.size, "every seat should be scored: ${result.scores}")
        // TOY_TARGET moves of 1 or 2 squares each, so the totals bracket the move count.
        assertTrue(
            result.scores.values.sum() in TOY_TARGET..(TOY_TARGET * 2),
            "scoreline should reflect $TOY_TARGET moves: ${result.scores}",
        )
        assertTrue(result.winnerTeam in 0 until TOY_SEATS, "a seat should have won: ${result.winnerTeam}")
        assertEquals(RoomPhase.FINISHED, awaitLobbyPhase(host, RoomPhase.FINISHED).phase)
    }

    @Test
    fun `a redacted view never carries another seat's hidden information`() = runBlocking {
        val server = server()
        val (host, _) = server.createLobby()
        server.route(host, StartGame)
        awaitLobbyPhase(host, RoomPhase.PLAYING)
        val update: ViewUpdate<ToyView> = host.await()
        // Seat 0 is the creator's; the view must carry its own secret and no map of everyone's.
        assertEquals(Seat(0), update.view.seat)
        assertEquals(11, update.view.yourSecret)
    }

    @Test
    fun `an illegal action is rejected and the game carries on`() = runBlocking {
        val server = server()
        val (host, _) = server.createLobby()
        server.route(host, StartGame)
        awaitLobbyPhase(host, RoomPhase.PLAYING)
        val prompt = awaitOwnTurn(host)

        server.route(host, SubmitAction(prompt.stateVersion, ToyAction.Move(99)))
        val error: ErrorMessage = host.await()
        assertEquals(ErrorCode.ILLEGAL_ACTION, error.code)

        // The same turn still accepts a legal move, so the room was not disturbed.
        server.route(host, SubmitAction(prompt.stateVersion, ToyAction.Move(1)))
        val next = withTimeout(AWAIT_MILLIS) {
            var update: ViewUpdate<ToyView> = host.await()
            while (update.stateVersion <= prompt.stateVersion) update = host.await()
            update
        }
        assertTrue(next.view.moves.isNotEmpty(), "the legal move should have been applied")
    }

    @Test
    fun `a stale stateVersion is rejected`() = runBlocking {
        val server = server()
        val (host, _) = server.createLobby()
        server.route(host, StartGame)
        awaitLobbyPhase(host, RoomPhase.PLAYING)
        val prompt = awaitOwnTurn(host)

        server.route(host, SubmitAction(prompt.stateVersion - 1, ToyAction.Move(1)))
        val error: ErrorMessage = host.await()
        assertEquals(ErrorCode.STALE_ACTION, error.code)
    }

    @Test
    fun `a mid-game disconnect substitutes a bot and the owner reclaims the seat on reconnect`() = runBlocking {
        // Two humans, so there is someone still at the table to observe the substitution: a room
        // deliberately clears the departing occupant before broadcasting, so the client that dropped
        // is never told about itself.
        val server = server()
        val (host, lobby) = server.createLobby()
        val guest = connection("guest")
        server.route(guest, JoinLobby(lobby.joinCode, "Bob"))
        val guestLobby: LobbyState<ToyConfig> = guest.await()
        val guestSeat = assertNotNull(guestLobby.yourSeat)
        server.route(guest, SetReady(true))
        server.route(host, StartGame)
        awaitLobbyPhase(host, RoomPhase.PLAYING)
        val room = assertNotNull(server.rooms.find(lobby.joinCode))

        guest.connected = false
        server.onDisconnected(guest)
        val substituted = awaitSeatStatus(host, guestSeat, OccupancyStatus.BOT_SUBSTITUTE)
        assertEquals(guestSeat, substituted.seat)

        // Reconnecting with the same session token reclaims the very same seat.
        val resumed = connection("guest")
        room.submit(RoomCommand.Reconnect(resumed, guestSeat))
        awaitSeatStatus(host, guestSeat, OccupancyStatus.HUMAN)
        assertEquals(guestSeat, resumed.seat)
    }

    @Test
    fun `an in-game disconnect inside the grace never surfaces a bot - the owner resumes seamlessly`() = runBlocking {
        val server = server(devConfig(gameDisconnectGraceMillis = 60_000))
        val (host, lobby) = server.createLobby()
        val guest = connection("guest")
        server.route(guest, JoinLobby(lobby.joinCode, "Bob"))
        val guestLobby: LobbyState<ToyConfig> = guest.await()
        val guestSeat = assertNotNull(guestLobby.yourSeat)
        server.route(guest, SetReady(true))
        server.route(host, StartGame)
        awaitLobbyPhase(host, RoomPhase.PLAYING)
        val room = assertNotNull(server.rooms.find(lobby.joinCode))

        guest.connected = false
        server.onDisconnected(guest)
        // The seat is held: no substitution broadcast arrives while the grace runs.
        val leaked = withTimeoutOrNull(500) {
            var next = host.outbound.receive()
            while (!(next is SeatStatus && next.status == OccupancyStatus.BOT_SUBSTITUTE)) {
                next = host.outbound.receive()
            }
            next
        }
        assertNull(leaked, "the bot must not take the seat inside the grace")

        // Reclaim inside the grace: the seat comes back HUMAN, having never been a bot.
        val resumed = connection("guest")
        room.submit(RoomCommand.Reconnect(resumed, guestSeat))
        awaitSeatStatus(host, guestSeat, OccupancyStatus.HUMAN)
        assertEquals(guestSeat, resumed.seat)
    }

    @Test
    fun `an in-game disconnect grace that expires hands the seat to the bot`() = runBlocking {
        val server = server(devConfig(gameDisconnectGraceMillis = 200))
        val (host, lobby) = server.createLobby()
        val guest = connection("guest")
        server.route(guest, JoinLobby(lobby.joinCode, "Bob"))
        val guestLobby: LobbyState<ToyConfig> = guest.await()
        val guestSeat = assertNotNull(guestLobby.yourSeat)
        server.route(guest, SetReady(true))
        server.route(host, StartGame)
        awaitLobbyPhase(host, RoomPhase.PLAYING)

        guest.connected = false
        server.onDisconnected(guest)
        val substituted = awaitSeatStatus(host, guestSeat, OccupancyStatus.BOT_SUBSTITUTE)
        assertEquals(guestSeat, substituted.seat)
    }

    @Test
    fun `a reconnector is told the current occupancy it missed while away`() = runBlocking {
        // SeatStatus is a transient broadcast, so a client disconnected when another seat's
        // substitution/reclaim was announced resumes with a stale picture ("(bot)" forever) unless
        // the reconnect refreshes it. Assert the refresh: a reconnector is told every human seat's
        // CURRENT status. One human stays seated throughout so the bots can't race the game to its
        // terminal state while the other seat changes hands.
        val server = server() // grace 0: substitution is immediate
        val (host, lobby) = server.createLobby()
        val guest = connection("guest")
        server.route(guest, JoinLobby(lobby.joinCode, "Bob"))
        val guestLobby: LobbyState<ToyConfig> = guest.await()
        val guestSeat = assertNotNull(guestLobby.yourSeat)
        server.route(guest, SetReady(true))
        server.route(host, StartGame)
        awaitLobbyPhase(host, RoomPhase.PLAYING)
        val room = assertNotNull(server.rooms.find(lobby.joinCode))

        // The guest's seat goes bot and comes back human while the host is present...
        guest.connected = false
        server.onDisconnected(guest)
        awaitSeatStatus(host, guestSeat, OccupancyStatus.BOT_SUBSTITUTE)
        val guestBack = connection("guest")
        room.submit(RoomCommand.Reconnect(guestBack, guestSeat))
        awaitSeatStatus(host, guestSeat, OccupancyStatus.HUMAN)

        // ...then the host cycles: its resume must carry the guest seat's CURRENT status.
        val hostSeat = assertNotNull(lobby.yourSeat)
        host.connected = false
        server.onDisconnected(host)
        val hostBack = connection("creator")
        room.submit(RoomCommand.Reconnect(hostBack, hostSeat))
        val refreshed = awaitSeatStatus(hostBack, guestSeat, OccupancyStatus.HUMAN)
        assertEquals(guestSeat, refreshed.seat)
    }

    // The two halves of restart survival are tested separately and deliberately. Driving one live
    // game into a brand-new server in a single test means racing the asynchronous snapshot writer and
    // ending the old "process" at an arbitrary instant, which is inherently timing-dependent; split
    // in two, each half asserts its own contract deterministically.

    @Test
    fun `a live game keeps a durable snapshot of itself as it plays`() = runBlocking {
        val dir: Path = Files.createTempDirectory("cardkit-room-snapshot")
        val server = server(devConfig(dataDir = dir.toString()))
        val (host, _) = server.createLobby()
        server.route(host, StartGame)
        awaitLobbyPhase(host, RoomPhase.PLAYING)
        val prompt = awaitOwnTurn(host)
        server.route(host, SubmitAction(prompt.stateVersion, ToyAction.Move(2)))

        val snapshot = awaitDurableGame(dir)
        assertEquals(RoomPhase.PLAYING, snapshot.phase)
        assertTrue(
            snapshot.gameState?.moves?.isNotEmpty() == true,
            "the snapshot should hold the live engine state: ${snapshot.gameState}",
        )
        // The seat roster is stored with its token ownership, which is what makes a reclaim possible.
        assertEquals("creator", snapshot.seats.first().ownerToken)
    }

    @Test
    fun `a snapshot on disk is adopted at boot and its owner reclaims the seat mid-game`() = runBlocking {
        // The snapshot is written by hand so the restore path is exercised exactly, with no
        // dependence on when a previous process happened to flush.
        val dir: Path = Files.createTempDirectory("cardkit-room-restore")
        val stored = RoomSnapshot(
            snapshotVersion = RoomSnapshot.CURRENT_VERSION,
            gameId = "game-restore-1",
            joinCode = "AB12",
            creatorToken = "creator",
            lobbyConfig = ToyConfig(),
            phase = RoomPhase.PLAYING,
            seats = listOf(
                RoomSnapshot.SeatSnapshot(name = "Alice", isBot = false, ownerToken = "creator"),
                RoomSnapshot.SeatSnapshot(name = "Ivy (bot)", isBot = true, ownerToken = null),
            ),
            stateVersion = 3,
            gameState = ToyState(moves = listOf(2, 1), toAct = 0, rngSeed = 7),
            savedAtMillis = System.currentTimeMillis(),
        )
        val serializer = RoomSnapshot.serializer(ToyDescriptor.stateSerializer, ToyDescriptor.configSerializer)
        Files.writeString(dir.resolve("${stored.gameId}.json"), Json.encodeToString(serializer, stored))

        val server = server(devConfig(dataDir = dir.toString()))
        server.restoreRooms()
        val restored = assertNotNull(
            server.rooms.find(stored.joinCode),
            "the room should be adopted under its original join code",
        )

        // Every restored seat comes back owned but empty, so the driver is deliberately not running
        // yet — an eager one would bot-play the whole game before anyone could reclaim a seat.
        val resumed = connection("creator")
        restored.submit(RoomCommand.Reconnect(resumed, Seat(0)))
        val state: LobbyState<ToyConfig> = resumed.await()
        assertEquals(RoomPhase.PLAYING, state.phase)
        assertEquals(Seat(0), state.yourSeat)

        // The reconnect replays the stored view first, and then the driver — which only starts once a
        // seat is reclaimed — republishes it a version later. A client therefore has to play against
        // the newest version it has seen, which is what its tracked authoritative version is for.
        val replayed = awaitOwnTurn(resumed)
        assertEquals(stored.stateVersion + 1, replayed.stateVersion, "the stored view is replayed first")
        assertEquals(listOf(2, 1), replayed.view.moves, "the restored game remembers the moves already played")
        val live = withTimeout(AWAIT_MILLIS) {
            var update = awaitOwnTurn(resumed)
            while (update.stateVersion <= replayed.stateVersion) update = awaitOwnTurn(resumed)
            update
        }
        assertEquals(listOf(2, 1), live.view.moves, "restarting the driver must not change the game")

        // And it really is live: the reclaimed seat can play on, and the bot answers.
        server.route(resumed, SubmitAction(live.stateVersion, ToyAction.Move(1)))
        val next = withTimeout(AWAIT_MILLIS) {
            var update: ViewUpdate<ToyView> = resumed.await()
            while (update.view.moves.size < 4) update = resumed.await()
            update
        }
        assertEquals(listOf(2, 1, 1), next.view.moves.take(3))
    }

    @Test
    fun `an unsupported configuration is refused before a room is created`() = runBlocking {
        val server = server()
        val conn = connection("creator")
        server.route(conn, ToyCreateLobby(displayName = "Alice", target = 0))
        val error: ErrorMessage = conn.await()
        assertEquals(ErrorCode.BAD_CONFIG, error.code)
        assertEquals(0, server.rooms.roomCount())
        assertNull(conn.roomId)
    }

    @Test
    fun `joining by an unknown code is refused`() = runBlocking {
        val server = server()
        val conn = connection("guest")
        server.route(conn, JoinLobby("ZZZZ", "Bob"))
        val error: ErrorMessage = conn.await()
        assertEquals(ErrorCode.NO_SUCH_LOBBY, error.code)
    }

    @Test
    fun `a second player joins by code, readies up, and the creator can start`() = runBlocking {
        val server = server()
        val (host, lobby) = server.createLobby()
        val guest = connection("guest")
        server.route(guest, JoinLobby(lobby.joinCode.lowercase(), "Bob")) // codes are case-insensitive
        val guestLobby: LobbyState<ToyConfig> = guest.await()
        assertEquals(lobby.joinCode, guestLobby.joinCode)
        assertEquals(2, guestLobby.seats.count { it.name.isNotEmpty() })

        // The creator's Start is their own readiness, but a guest must ready up first.
        server.route(host, StartGame)
        val error: ErrorMessage = host.await()
        assertEquals(ErrorCode.BAD_CONFIG, error.code)

        server.route(guest, SetReady(true))
        server.route(host, StartGame)
        assertEquals(RoomPhase.PLAYING, awaitLobbyPhase(host, RoomPhase.PLAYING).phase)
    }

    @Test
    fun `an empty room is disbanded once its idle window passes`() = runBlocking {
        val server = server(devConfig(idleDisbandMillisOverride = 200, lobbyDisconnectGraceMillis = 60_000))
        val (host, _) = server.createLobby()
        host.connected = false
        server.onDisconnected(host) // nobody connected ⇒ the idle clock starts
        val disbanded: LobbyDisbanded = host.await()
        assertEquals(DisbandReason.IDLE_TIMEOUT, disbanded.reason)
        // The room tells its clients before it deregisters, so give the actor a moment to finish.
        withTimeout(AWAIT_MILLIS) {
            while (server.rooms.roomCount() > 0) delay(SNAPSHOT_POLL_MILLIS)
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    private suspend fun awaitLobbyPhase(
        connection: PlayerConnection,
        phase: RoomPhase,
    ): LobbyState<ToyConfig> = withTimeout(AWAIT_MILLIS) {
        var state: LobbyState<ToyConfig> = connection.await()
        while (state.phase != phase) state = connection.await()
        state
    }

    /** Wait for a durable snapshot under [dir] that holds a game in progress, and return it. */
    private suspend fun awaitDurableGame(dir: Path): RoomSnapshot<ToyState, ToyConfig> {
        val serializer = RoomSnapshot.serializer(ToyDescriptor.stateSerializer, ToyDescriptor.configSerializer)
        val lenient = Json { ignoreUnknownKeys = true }
        var seen = "nothing on disk"
        val found = withTimeoutOrNull(AWAIT_MILLIS) {
            while (true) {
                val results = Files.list(dir).use { paths ->
                    paths.filter { it.toString().endsWith(".json") }.toList()
                }.map { file ->
                    runCatching { lenient.decodeFromString(serializer, file.readText()) }
                }
                seen = results.joinToString { result ->
                    result.fold(
                        { "phase=${it.phase} v=${it.stateVersion} moves=${it.gameState?.moves}" },
                        { "unreadable: ${it.message}" },
                    )
                }
                val playing = results.mapNotNull { it.getOrNull() }
                    .firstOrNull { it.phase == RoomPhase.PLAYING && it.gameState != null }
                if (playing != null) return@withTimeoutOrNull playing
                delay(SNAPSHOT_POLL_MILLIS)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        return assertNotNull(found, "no durable snapshot of a live game appeared; on disk: $seen")
    }

    private suspend fun awaitSeatStatus(
        connection: PlayerConnection,
        seat: Seat,
        status: OccupancyStatus,
    ): SeatStatus = withTimeout(AWAIT_MILLIS) {
        var next: SeatStatus = connection.await()
        while (next.seat != seat || next.status != status) next = connection.await()
        next
    }

    /** Wait for a view update that prompts this connection's own turn. */
    private suspend fun awaitOwnTurn(connection: PlayerConnection): ViewUpdate<ToyView> =
        withTimeout(AWAIT_MILLIS) {
            var update: ViewUpdate<ToyView> = connection.await()
            while (update.view.legalActions.isEmpty()) update = connection.await()
            update
        }

    /** Answer every prompt with a legal move until the server announces the result. */
    private suspend fun playToCompletion(
        server: GameServer<ToyState, ToyAction, ToyView, ToyConfig>,
        connection: PlayerConnection,
    ): GameOver = withTimeout(AWAIT_MILLIS) {
        while (true) {
            when (val message = connection.outbound.receive()) {
                is GameOver -> return@withTimeout message
                is ViewUpdate<*> -> {
                    val view = message.view as ToyView
                    if (view.legalActions.isNotEmpty()) {
                        server.route(connection, SubmitAction(message.stateVersion, view.legalActions.first()))
                    }
                }
                else -> Unit
            }
        }
        error("unreachable")
    }

    private companion object {
        const val AWAIT_MILLIS = 10_000L
        const val SNAPSHOT_POLL_MILLIS = 20L
    }
}
