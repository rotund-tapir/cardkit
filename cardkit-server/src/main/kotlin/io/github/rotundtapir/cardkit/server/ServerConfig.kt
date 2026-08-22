// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.server

/**
 * All server tunables, read from the environment so the Docker container is configured entirely by
 * env vars (see each game's docker-compose.yml / self-hosting docs). Defaults are safe for a public
 * server on a 1 vCPU / 1 GB VPS.
 *
 * The three game-identity fields — [allowedOrigins], [minAppVersion], [serverVersion] — default to
 * permissive placeholders because this module knows nothing about any particular game. A game's
 * `main()` passes its own values as the [Companion.fromEnv] defaults, and the environment overrides
 * those in turn.
 */
data class ServerConfig(
    val port: Int = 8080,
    /** Trust `X-Forwarded-*` headers (true only behind the Caddy reverse proxy). */
    val trustProxy: Boolean = false,
    /** Origins allowed to open a WebSocket (CSWSH defence). `*` disables the check (LAN/self-host). */
    val allowedOrigins: List<String> = listOf("*"),
    /** Oldest app version the server still accepts; older clients are told to update. */
    val minAppVersion: String = "0.0.0",
    val serverVersion: String = "0.0.0",
    val maxConnectionsPerIp: Int = 8,
    val messageRatePerSecond: Int = 10,
    val messageBurst: Int = 20,
    val lobbiesPerIpPer10Min: Int = 5,
    val maxRooms: Int = 500,
    val maxFrameBytes: Long = 16 * 1024,
    /** How long an untouched session token survives before the periodic sweep drops it. */
    val sessionTtlMillis: Long = 60 * 60_000L,
    /**
     * How long a lobby/post-game seat is held for its owner after a bare socket drop, so a page
     * reload, a transient network blip, or an app switch (a host sharing the invite code via
     * another app can be away for minutes) can reconnect and reclaim it — instead of the room
     * being disbanded (creator) or the seat freed (guest) the instant the old socket closes.
     * The lobby idle-disband remains the backstop for genuinely abandoned rooms.
     */
    val lobbyDisconnectGraceMillis: Long = 15 * 60_000L,
    /**
     * How long a PLAYING room holds a dropped human's seat before the bot takes over. Android
     * backgrounds an app-switch away; without a grace the bot steals the very turn the player
     * was thinking about. The current turn simply keeps waiting during the grace (the turn
     * timeout still bounds it), so opponents wait at most this long extra for a vanished player.
     */
    val gameDisconnectGraceMillis: Long = 3 * 60_000L,
    /**
     * Directory for room snapshots, so in-flight games survive a restart (rejoin via the existing
     * session tokens). Null (the default) keeps in-memory-only behaviour: a restart drops every
     * game. A deployment mounts a volume and sets `DATA_DIR=/data`.
     */
    val dataDir: String? = null,
    /** Relaxes IP caps and honours a client-supplied game seed. For local dev / e2e only. */
    val devMode: Boolean = false,
    /** Test hook: force the per-turn timeout to this many ms, ignoring the lobby's seconds setting. */
    val turnTimeoutMillisOverride: Long? = null,
    /** Test hook: force the idle-disband timeout to this many ms, ignoring the lobby's minutes setting. */
    val idleDisbandMillisOverride: Long? = null,
) {
    /** True when the Origin check is disabled. */
    val allowAnyOrigin: Boolean get() = allowedOrigins.any { it == "*" }

    /** Whether [origin] (may be null for non-browser clients) is permitted to connect. */
    fun originAllowed(origin: String?): Boolean =
        allowAnyOrigin || origin == null || origin in allowedOrigins

    companion object {
        /**
         * Build a config from a `getenv`-style lookup (injectable so tests don't touch the real env),
         * falling back to [defaults] for anything unset. A game passes its own identity in
         * [defaults] — origins, minimum app version, its build version.
         */
        fun fromEnv(
            defaults: ServerConfig = ServerConfig(),
            getenv: (String) -> String? = System::getenv,
        ): ServerConfig {
            fun int(name: String, default: Int) = getenv(name)?.toIntOrNull() ?: default
            fun long(name: String, default: Long) = getenv(name)?.toLongOrNull() ?: default
            fun bool(name: String, default: Boolean) =
                getenv(name)?.let { it.equals("true", ignoreCase = true) || it == "1" } ?: default
            return defaults.copy(
                port = int("PORT", defaults.port),
                trustProxy = bool("TRUST_PROXY", defaults.trustProxy),
                allowedOrigins = getenv("ALLOWED_ORIGINS")
                    ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
                    ?: defaults.allowedOrigins,
                minAppVersion = getenv("MIN_APP_VERSION") ?: defaults.minAppVersion,
                serverVersion = getenv("SERVER_VERSION") ?: defaults.serverVersion,
                maxConnectionsPerIp = int("MAX_CONNECTIONS_PER_IP", defaults.maxConnectionsPerIp),
                messageRatePerSecond = int("MSG_RATE_PER_SEC", defaults.messageRatePerSecond),
                messageBurst = int("MSG_BURST", defaults.messageBurst),
                lobbiesPerIpPer10Min = int("LOBBIES_PER_IP_PER_10MIN", defaults.lobbiesPerIpPer10Min),
                maxRooms = int("MAX_ROOMS", defaults.maxRooms),
                maxFrameBytes = long("MAX_FRAME_BYTES", defaults.maxFrameBytes),
                sessionTtlMillis = long("SESSION_TTL_MILLIS", defaults.sessionTtlMillis),
                lobbyDisconnectGraceMillis = long("LOBBY_GRACE_MILLIS", defaults.lobbyDisconnectGraceMillis),
                gameDisconnectGraceMillis = long("GAME_GRACE_MILLIS", defaults.gameDisconnectGraceMillis),
                dataDir = getenv("DATA_DIR")?.takeIf { it.isNotBlank() } ?: defaults.dataDir,
                devMode = bool("DEV_MODE", defaults.devMode),
            )
        }
    }
}
