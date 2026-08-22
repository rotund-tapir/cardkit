// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

import kotlin.time.Duration.Companion.seconds

/**
 * CIO engine — a pure-Kotlin/JVM stack that works on Android 7+ (minSdk 24) with no extra deps.
 *
 * The ping bounds dead-socket detection: Android silently kills a backgrounded app's TCP
 * connections, and without pings the receive loop waits on the zombie indefinitely — the app
 * looks connected but no moves arrive until the OS gives up. With a ping every 20s a dead
 * socket fails within roughly two intervals and the reconnect loop takes over. The browser
 * engine (wasm) has no frame-level ping API; tabs get liveness from the browser itself plus
 * the client's foreground nudge.
 */
actual fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(WebSockets) {
        pingIntervalMillis = 20.seconds.inWholeMilliseconds
    }
}
