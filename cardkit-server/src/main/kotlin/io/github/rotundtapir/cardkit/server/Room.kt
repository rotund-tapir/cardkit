// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.server

import io.github.rotundtapir.cardkit.core.GameDriver
import io.github.rotundtapir.cardkit.core.GameRules
import io.github.rotundtapir.cardkit.core.Player
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Strategy
import io.github.rotundtapir.cardkit.net.DisbandReason
import io.github.rotundtapir.cardkit.net.Emote
import io.github.rotundtapir.cardkit.net.EmoteReceived
import io.github.rotundtapir.cardkit.net.ErrorCode
import io.github.rotundtapir.cardkit.net.ErrorMessage
import io.github.rotundtapir.cardkit.net.GameOver
import io.github.rotundtapir.cardkit.net.LobbyDisbanded
import io.github.rotundtapir.cardkit.net.LobbyState
import io.github.rotundtapir.cardkit.net.MAX_IDLE_DISBAND_MINUTES
import io.github.rotundtapir.cardkit.net.MAX_TURN_TIMEOUT_SECONDS
import io.github.rotundtapir.cardkit.net.MIN_IDLE_DISBAND_MINUTES
import io.github.rotundtapir.cardkit.net.MIN_TURN_TIMEOUT_SECONDS
import io.github.rotundtapir.cardkit.net.Names
import io.github.rotundtapir.cardkit.net.OccupancyStatus
import io.github.rotundtapir.cardkit.net.ResumedState
import io.github.rotundtapir.cardkit.net.RoomPhase
import io.github.rotundtapir.cardkit.net.SeatInfo
import io.github.rotundtapir.cardkit.net.SeatStatus
import io.github.rotundtapir.cardkit.net.ServerMessage
import io.github.rotundtapir.cardkit.net.ViewUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * One game room: a lobby that becomes a game and then a post-game screen. All state lives here and
 * is mutated only on the actor coroutine draining [commands]. The [GameDriver] runs in a child
 * coroutine and communicates back exclusively through [RoomCommand.StateProduced] /
 * [RoomCommand.GameFinished], keeping the single-writer invariant intact.
 *
 * Entirely game-agnostic: every game-specific decision — which rules a lobby config means, how a
 * game is dealt, which bot plays an empty seat, what the final scoreline is — is delegated to
 * [descriptor].
 */
@Suppress("LargeClass") // the room actor IS the domain: splitting it would scatter one state machine
class Room<S : Any, A : Any, V : Any, C : Any>(
    val gameId: String,
    var joinCode: String,
    private val creatorToken: String,
    initialConfig: C,
    private val requestedSeed: Long?,
    private val scope: CoroutineScope,
    private val config: ServerConfig,
    private val descriptor: GameDescriptor<S, A, V, C>,
    private val sessionRegistry: SessionRegistry,
    private val metrics: Metrics,
    private val abuseLog: AbuseLog,
    private val persistence: RoomPersistence<S, C>,
    private val nowMillis: () -> Long,
    private val onClosed: (Room<S, A, V, C>) -> Unit,
) {
    private val logger = LoggerFactory.getLogger("room")
    private val commands = Channel<RoomCommand<S, A>>(Channel.UNLIMITED)

    private var lobbyConfig = initialConfig

    // Built from the config the room was created with. Only the timeouts can change later
    // (RoomCommand.Configure), never the house rules, so the bot never goes stale.
    private val bot: Strategy<V, A> = descriptor.bot(initialConfig)

    // Read cross-thread (isPlaying/resumedState from the Ktor handler & /health), written on the actor.
    @Volatile
    private var phase = RoomPhase.LOBBY
    private val slots: List<Slot> = (0 until descriptor.playerCount(initialConfig)).map { Slot(Seat(it)) }

    private var rules: GameRules<S, A, V>? = null
    private var latestState: S? = null
    private var stateVersion = 0
    private val latestViews = HashMap<Seat, ViewUpdate<V>>()
    private var currentActor: Seat? = null
    private var currentTurnDeadline: Long? = null
    private var driverJob: Job? = null
    private var closed = false
    private var emptySince: Long? = null

    /** The most recent accepted move, for idempotent handling of duplicate submissions. */
    private var lastAccepted: AcceptedMove<A>? = null

    /**
     * Set while a snapshot-restored game is waiting for its first human to reconnect. The driver is
     * deliberately NOT running yet: every restored seat is occupant-less, so an eagerly-started
     * driver would bot-play the whole game to completion before anyone could reclaim a seat. The
     * first successful reconnect starts it; the idle sweep reaps the room if nobody ever returns.
     */
    private var awaitingRestoredDriver = false

    /** The final scoreline once the game ends, so a late reconnector still sees the result. */
    private var finalResult: GameOver? = null

    private data class AcceptedMove<A>(val stateVersion: Int, val seat: Seat, val action: A)

    private val turnTimeoutMillis: Long
        get() = config.turnTimeoutMillisOverride ?: (descriptor.turnTimeoutSeconds(lobbyConfig) * 1000L)

    private val idleDisbandMillis: Long
        get() = config.idleDisbandMillisOverride ?: (descriptor.idleDisbandMinutes(lobbyConfig) * 60_000L)

    private inner class Slot(val seat: Seat) {
        var occupant: PlayerConnection? = null
        var name: String? = null
        var ready: Boolean = false
        var isBot: Boolean = false
        var host: SeatHost<V, A>? = null

        /** The session token that owns this seat, so a reconnect can only reclaim its own seat. */
        var ownerToken: String? = null

        /**
         * Bumped on every in-game disconnect; a [RoomCommand.GameDisconnectGraceExpired] carries the
         * generation it was scheduled for, so a stale expiry from an earlier drop (reconnect, then
         * drop again) can't cut the newer grace short.
         */
        var graceGeneration: Int = 0
    }

    /** Enqueue a command for the actor loop. Never blocks (the channel is unbounded). */
    fun submit(command: RoomCommand<S, A>) {
        commands.trySend(command)
    }

    /** Start the actor loop and the idle ticker. Called once, right after construction. */
    fun start() {
        scope.launch {
            for (command in commands) {
                runCatching { handle(command) }
                    .onFailure { logger.warn("room $joinCode command ${command::class.simpleName} failed", it) }
                if (closed) break
            }
        }
        scope.launch {
            val interval = (idleDisbandMillis / IDLE_CHECKS_PER_WINDOW).coerceIn(MIN_IDLE_INTERVAL, MAX_IDLE_INTERVAL)
            while (isActive && !closed) {
                delay(interval)
                submit(RoomCommand.IdleCheck)
            }
        }
    }

    /** True while at least one live game is running — used by drain/idle accounting. */
    fun isPlaying(): Boolean = phase == RoomPhase.PLAYING

    // A pure dispatch over the sealed command type — the branch count is the point, not accidental.
    @Suppress("CyclomaticComplexMethod")
    private suspend fun handle(command: RoomCommand<S, A>) {
        when (command) {
            is RoomCommand.Join -> onJoin(command)
            is RoomCommand.Reconnect -> onReconnect(command)
            is RoomCommand.SetName -> onSetName(command)
            is RoomCommand.PickSeat -> onPickSeat(command)
            is RoomCommand.SetReady -> onSetReady(command)
            is RoomCommand.Configure -> onConfigure(command)
            is RoomCommand.Start -> onStart(command)
            is RoomCommand.Submit -> onSubmit(command)
            is RoomCommand.SendEmote -> onEmote(command)
            is RoomCommand.Leave -> onLeave(command)
            is RoomCommand.Disband -> onDisband(command)
            is RoomCommand.Rematch -> onRematch(command)
            is RoomCommand.Disconnected -> onDisconnected(command)
            is RoomCommand.DisconnectGraceExpired -> onDisconnectGraceExpired(command)
            is RoomCommand.GameDisconnectGraceExpired -> onGameGraceExpired(command)
            is RoomCommand.StateProduced -> onStateProduced(command.state)
            is RoomCommand.GameFinished -> onGameFinished(command.state)
            is RoomCommand.ForceDisband -> disband(command.reason)
            is RoomCommand.ReleaseUnclaimedSeat -> onReleaseUnclaimedSeat(command.seat)
            RoomCommand.IdleCheck -> onIdleCheck()
        }
        persist()
    }

    // --- Lobby ------------------------------------------------------------------------------------

    private fun onJoin(cmd: RoomCommand.Join) {
        if (phase != RoomPhase.LOBBY) {
            reject(cmd.connection, ErrorCode.WRONG_PHASE, "Game already started")
            return
        }
        val validated = validateName(cmd.connection, cmd.displayName) ?: return
        if (nameInUse(validated, except = null)) {
            reject(cmd.connection, ErrorCode.NAME_TAKEN, "Someone in this lobby is already called $validated")
            return
        }
        // ownerToken == null keeps a snapshot-restored seat (owned but occupant-less) reserved for
        // its returning owner; live lobby seats always hold their occupant while owned, so the extra
        // condition changes nothing outside a restore. ReleaseUnclaimedSeat frees it eventually.
        val free = slots.firstOrNull { it.occupant == null && !it.isBot && it.ownerToken == null }
        if (free == null) {
            reject(cmd.connection, ErrorCode.LOBBY_FULL, "No free seats")
            return
        }
        seat(cmd.connection, free, validated)
        logger.info(
            "join code={} seat={} name={} creator={} conn={}",
            joinCode,
            free.seat.index,
            validated,
            isCreator(cmd.connection),
            cmd.connection.id,
        )
        broadcastLobby()
        // recompute (not markActivity): if the joining socket died before this ran, no one is
        // connected, so emptySince starts ticking and the idle sweep reclaims the stillborn room.
        recomputeEmptiness()
    }

    private fun onSetName(cmd: RoomCommand.SetName) {
        val slot = slotOf(cmd.connection) ?: return rejectNotInLobby(cmd.connection)
        if (phase != RoomPhase.LOBBY) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "Cannot rename mid-game")
        val validated = validateName(cmd.connection, cmd.displayName) ?: return
        // `except = slot` so changing only the casing of your own name is allowed.
        if (nameInUse(validated, except = slot)) {
            return reject(cmd.connection, ErrorCode.NAME_TAKEN, "Someone in this lobby is already called $validated")
        }
        slot.name = validated
        broadcastLobby()
    }

    @Suppress("ReturnCount") // guard-clause validation reads clearer than nesting
    private fun onPickSeat(cmd: RoomCommand.PickSeat) {
        if (phase != RoomPhase.LOBBY) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "Cannot move mid-game")
        val from = slotOf(cmd.connection) ?: return rejectNotInLobby(cmd.connection)
        val target = slots.getOrNull(cmd.seat.index)
            ?: return reject(cmd.connection, ErrorCode.BAD_CONFIG, "No such seat")
        if (target === from) return
        if (target.occupant != null || target.isBot || target.ownerToken != null) {
            return reject(cmd.connection, ErrorCode.SEAT_TAKEN, "Seat taken")
        }
        val name = from.name
        clearSlot(from)
        seat(cmd.connection, target, name)
        broadcastLobby()
    }

    private fun onSetReady(cmd: RoomCommand.SetReady) {
        if (phase != RoomPhase.LOBBY) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "Lobby only")
        val slot = slotOf(cmd.connection) ?: return rejectNotInLobby(cmd.connection)
        slot.ready = cmd.ready
        broadcastLobby()
        markActivity()
    }

    private fun onConfigure(cmd: RoomCommand.Configure) {
        if (!isCreator(cmd.connection)) return reject(cmd.connection, ErrorCode.NOT_CREATOR, "Only the creator")
        if (phase != RoomPhase.LOBBY) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "Lobby only")
        lobbyConfig = descriptor.withTimeouts(
            lobbyConfig,
            cmd.turnTimeoutSeconds?.coerceIn(MIN_TURN_TIMEOUT_SECONDS, MAX_TURN_TIMEOUT_SECONDS),
            cmd.idleDisbandMinutes?.coerceIn(MIN_IDLE_DISBAND_MINUTES, MAX_IDLE_DISBAND_MINUTES),
        )
        broadcastLobby()
    }

    // --- Game start & play ------------------------------------------------------------------------

    private fun onStart(cmd: RoomCommand.Start) {
        if (!isCreator(cmd.connection)) return reject(cmd.connection, ErrorCode.NOT_CREATOR, "Only the creator")
        if (phase != RoomPhase.LOBBY) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "Already started")
        // The creator pressing Start is their own readiness; only the other present humans (guests)
        // must have readied up. Empty seats become bots.
        val creatorSlot = slotOf(cmd.connection)
        val guests = slots.filter { it.occupant != null && it !== creatorSlot }
        if (guests.any { !it.ready }) {
            return reject(cmd.connection, ErrorCode.BAD_CONFIG, "All players must be ready")
        }
        launchGame()
    }

    private fun launchGame() {
        val gameRules = descriptor.rulesFor(lobbyConfig)
        rules = gameRules
        val seed = (if (config.devMode) requestedSeed else null) ?: Random.nextLong()
        val botNames = pickBotNames(slots.count { it.occupant == null })
        var botIndex = 0
        val players = HashMap<Seat, Player<V, A>>()
        for (slot in slots) {
            val host = SeatHost<V, A>(
                seat = slot.seat,
                bot = bot,
                botRandom = Random(seed + slot.seat.index + 1),
                turnTimeout = turnTimeoutMillis.milliseconds,
            )
            if (slot.occupant == null) {
                slot.isBot = true
                slot.name = Names.botLabel(botNames[botIndex++])
                host.permanentBot = true
            } else {
                // A seat whose owner is in the disconnect grace stays human (they can reclaim it),
                // but starts bot-covered so the table never waits out a turn timeout on a dead socket.
                host.occupant = slot.occupant?.takeIf { it.connected }
            }
            slot.host = host
            players[slot.seat] = host
        }
        phase = RoomPhase.PLAYING
        lastAccepted = null
        broadcastLobby()
        metrics.gameStarted()
        launchDriver(gameRules, players, descriptor.newGame(lobbyConfig, seed))
    }

    private fun launchDriver(
        gameRules: GameRules<S, A, V>,
        players: Map<Seat, Player<V, A>>,
        initial: S,
    ) {
        driverJob = scope.launch {
            runCatching {
                val terminal = GameDriver(gameRules, players).play(initial) { state ->
                    submit(RoomCommand.StateProduced(state))
                }
                submit(RoomCommand.GameFinished(terminal))
            }.onFailure { e ->
                if (e is CancellationException) throw e // room closing / rematch — normal teardown
                // A rules/driver failure must never leave the room stuck in PLAYING: that would freeze
                // every client and, worse, keep activeGames() above zero so a deploy drain never ends.
                // Tear the room down instead. (Should be unreachable — apply() is trial-validated.)
                logger.error("room $joinCode driver crashed; disbanding", e)
                submit(RoomCommand.ForceDisband(DisbandReason.SERVER_SHUTDOWN))
            }
        }
    }

    private fun onStateProduced(state: S) {
        val gameRules = rules ?: return
        latestState = state
        stateVersion++
        val terminal = gameRules.isTerminal(state)
        currentActor = if (terminal) null else gameRules.currentActor(state)
        currentTurnDeadline = if (currentActor != null) nowMillis() + turnTimeoutMillis else null
        // Open the new turn before anyone can answer it: this drops actions left over from earlier
        // turns while keeping every action that arrives from here on (see SeatHost.beginTurn).
        for (slot in slots) slot.host?.beginTurn()
        for (slot in slots) {
            val view = gameRules.view(state, slot.seat)
            val remaining = if (slot.seat == currentActor) turnTimeoutMillis else null
            val update = ViewUpdate(stateVersion, view, remaining)
            latestViews[slot.seat] = update
            slot.occupant?.let { deliver(it, update) }
        }
    }

    @Suppress("ReturnCount") // guard-clause validation reads clearer than nesting
    private fun onSubmit(cmd: RoomCommand.Submit<A>) {
        if (phase != RoomPhase.PLAYING) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "No game in progress")
        val slot = slotOf(cmd.connection) ?: return rejectNotInLobby(cmd.connection)
        val gameRules = rules ?: return
        val state = latestState ?: return
        // Idempotency: an accidental duplicate of a move we already accepted (a resend, or a
        // double-tap during a laggy round-trip) is a harmless no-op, not a stale rejection. Keyed on
        // (stateVersion, seat, action) which is unique per applied move — stateVersion is monotonic.
        if (lastAccepted == AcceptedMove(cmd.stateVersion, slot.seat, cmd.action)) return
        if (cmd.stateVersion != stateVersion || currentActor != slot.seat) {
            return reject(cmd.connection, ErrorCode.STALE_ACTION, "Not your turn / stale", fatal = false)
        }
        // Validate by trial-applying: apply is pure, the state can't change while the driver is
        // blocked in decide(), and this is the only safe guard — the driver's real apply() throws on
        // an illegal action and would kill the room coroutine.
        val illegal = runCatching { gameRules.apply(state, slot.seat, cmd.action) }.isFailure
        if (illegal) {
            abuseLog.log(AbuseLog.Event.ILLEGAL_ACTION, cmd.connection.remoteIp, "seat=${slot.seat.index}")
            return reject(cmd.connection, ErrorCode.ILLEGAL_ACTION, "Illegal action", fatal = false)
        }
        // Only remember it as accepted (for idempotency) if it actually reached the seat host —
        // otherwise a resend must be allowed through rather than deduped into oblivion.
        if (slot.host?.submit(cmd.action) == true) {
            lastAccepted = AcceptedMove(cmd.stateVersion, slot.seat, cmd.action)
            markActivity()
        }
    }

    private fun onGameFinished(state: S) {
        phase = RoomPhase.FINISHED
        driverJob = null
        // A finished game needs no restart durability (the result was delivered; a rematch that
        // returns the room to LOBBY re-saves it). Restores therefore never see FINISHED.
        persistence.delete(gameId)
        metrics.gameCompleted()
        val result = descriptor.gameOver(state)
        finalResult = result
        broadcastAll(result)
        broadcastLobby()
    }

    // --- Emotes, leaving, rematch, disband --------------------------------------------------------

    private fun onEmote(cmd: RoomCommand.SendEmote) {
        val slot = slotOf(cmd.connection) ?: return rejectNotInLobby(cmd.connection)
        if (cmd.emote == Emote.UNKNOWN) return
        broadcastAll(EmoteReceived(slot.seat, cmd.emote))
    }

    private fun onLeave(cmd: RoomCommand.Leave) {
        val slot = slotOf(cmd.connection) ?: return
        if (phase == RoomPhase.PLAYING) {
            slot.occupant = null
            slot.host?.occupant = null
            slot.host?.interrupt() // don't make the table wait out the timeout on someone who left
            broadcastSeatStatus(slot.seat, OccupancyStatus.BOT_SUBSTITUTE)
        } else if (isCreator(cmd.connection)) {
            disband(DisbandReason.CREATOR_DISBANDED)
            return
        } else {
            clearSlot(slot)
            broadcastLobby()
        }
        detachConnection(cmd.connection)
        recomputeEmptiness()
    }

    private fun onDisband(cmd: RoomCommand.Disband) {
        if (!isCreator(cmd.connection)) return reject(cmd.connection, ErrorCode.NOT_CREATOR, "Only the creator")
        disband(DisbandReason.CREATOR_DISBANDED)
    }

    private fun onRematch(cmd: RoomCommand.Rematch) {
        if (!isCreator(cmd.connection)) return reject(cmd.connection, ErrorCode.NOT_CREATOR, "Only the creator")
        if (phase != RoomPhase.FINISHED) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "Game not finished")
        // Keep only still-connected humans; reopen every other seat fully (a game-fill bot, or a human
        // who dropped and never reclaimed). Reopening via clearSlot also drops the departed player's
        // stale ownerToken/binding, so they can't reconnect into a seat that is now a fresh-game bot.
        for (slot in slots) {
            slot.ready = false
            slot.host = null
            if (slot.occupant?.connected != true) {
                slot.isBot = false
                clearSlot(slot)
            }
        }
        rules = null
        latestState = null
        latestViews.clear()
        currentActor = null
        currentTurnDeadline = null
        lastAccepted = null
        finalResult = null
        phase = RoomPhase.LOBBY
        broadcastLobby()
    }

    // --- Connection lifecycle ---------------------------------------------------------------------

    private fun onReconnect(cmd: RoomCommand.Reconnect) {
        val slot = slots.getOrNull(cmd.seat.index) ?: return failedResume(cmd.connection)
        // The token must still own this seat. Otherwise the seat was reassigned (the player left and
        // someone else took it, or a rematch reopened it) and honouring the stale binding would evict
        // the rightful occupant and hand their hand to the wrong client. Refuse and reset that client.
        if (slot.ownerToken != cmd.connection.sessionToken) {
            sessionRegistry.clear(cmd.connection.sessionToken)
            return failedResume(cmd.connection)
        }
        val zombie = slot.occupant
        if (zombie != null && zombie.id != cmd.connection.id) zombie.requestClose()
        slot.occupant = cmd.connection
        slot.host?.occupant = cmd.connection
        slot.host?.awaitingReclaim = false
        cmd.connection.roomId = gameId
        cmd.connection.seat = cmd.seat
        sessionRegistry.bind(cmd.connection.sessionToken, gameId, cmd.seat)
        deliver(cmd.connection, lobbyStateFor(cmd.connection))
        replayView(cmd.connection, cmd.seat)
        finalResult?.let { deliver(cmd.connection, it) } // a late reconnector still sees the result
        if (phase == RoomPhase.PLAYING) broadcastSeatStatus(cmd.seat, OccupancyStatus.HUMAN)
        if (phase == RoomPhase.PLAYING) refreshOccupancy(cmd.connection, cmd.seat)
        maybeStartRestoredDriver()
        recomputeEmptiness()
    }

    /** First reconnect into a restored game: the occupant is seated, so the driver may now run. */
    private fun maybeStartRestoredDriver() {
        if (!awaitingRestoredDriver || phase != RoomPhase.PLAYING) return
        val gameRules = rules ?: return
        val state = latestState ?: return
        awaitingRestoredDriver = false
        val players = HashMap<Seat, Player<V, A>>()
        for (slot in slots) slot.host?.let { players[slot.seat] = it }
        launchDriver(gameRules, players, state)
    }

    /** Tell a client its resume can't be honoured (seat gone/reassigned) so it returns to entry. */
    private fun failedResume(connection: PlayerConnection) {
        connection.roomId = null
        connection.seat = null
        deliver(connection, LobbyDisbanded(DisbandReason.UNKNOWN))
    }

    private fun onDisconnected(cmd: RoomCommand.Disconnected) {
        val slot = slotOf(cmd.connection) ?: return
        if (phase == RoomPhase.PLAYING) {
            if (config.gameDisconnectGraceMillis <= 0) {
                substituteBot(slot)
            } else {
                // Hold the seat: the host parks the turn (awaitingReclaim) instead of botting it,
                // so an app-switch or brief drop resumes exactly where it left off. Other players
                // wait at most the grace on top of the normal turn timeout. No status broadcast —
                // the seat is still the human's until the grace expires.
                slot.occupant = null
                slot.host?.occupant = null
                slot.host?.awaitingReclaim = true
                slot.graceGeneration++
                val generation = slot.graceGeneration
                val seat = slot.seat
                scope.launch {
                    delay(config.gameDisconnectGraceMillis)
                    submit(RoomCommand.GameDisconnectGraceExpired(seat, generation))
                }
            }
        } else {
            // Lobby/post-game: hold the seat (and its session→seat binding) for a short grace
            // window instead of acting on the drop immediately, so a page reload — which closes the
            // socket and reconnects seconds later with the same session token — reclaims the seat
            // rather than disbanding the room (creator) or losing it (guest). The roster broadcast
            // shows the seat as disconnected in the meantime.
            broadcastLobby()
            scope.launch {
                delay(config.lobbyDisconnectGraceMillis)
                submit(RoomCommand.DisconnectGraceExpired(cmd.connection))
            }
        }
        recomputeEmptiness()
    }

    private fun onDisconnectGraceExpired(cmd: RoomCommand.DisconnectGraceExpired) {
        // Only act if the seat still holds the connection that dropped: a reconnect within the
        // grace put a *new* connection (with a new id) in the slot, and an explicit Leave/Disband
        // cleared it — either way this command is stale and must do nothing.
        val slot = slotOf(cmd.connection) ?: return
        if (phase == RoomPhase.PLAYING) {
            // The game started while the seat was in grace (launchGame keeps a held seat human):
            // now that the owner is confirmed gone, let the bot cover it. Reclaim stays possible.
            substituteBot(slot)
        } else {
            clearSlot(slot)
            if (isCreator(cmd.connection) && phase == RoomPhase.LOBBY) {
                disband(DisbandReason.CREATOR_DISBANDED)
                return
            }
            broadcastLobby()
        }
        recomputeEmptiness()
    }

    private fun onGameGraceExpired(cmd: RoomCommand.GameDisconnectGraceExpired) {
        val slot = slots.getOrNull(cmd.seat.index) ?: return
        // Stale (an older drop's timer) or already resolved (reclaimed, or the game ended).
        if (cmd.generation != slot.graceGeneration) return
        if (slot.occupant != null || phase != RoomPhase.PLAYING) return
        if (slot.host?.awaitingReclaim != true) return
        substituteBot(slot)
    }

    /** In-game handling of a vanished occupant: the bot plays the seat until its owner reclaims it. */
    private fun substituteBot(slot: Slot) {
        slot.occupant = null
        slot.host?.occupant = null
        slot.host?.awaitingReclaim = false
        slot.host?.interrupt() // wake a parked turn so the bot covers it now, not after the timeout
        broadcastSeatStatus(slot.seat, OccupancyStatus.BOT_SUBSTITUTE)
    }

    private fun onIdleCheck() {
        val since = emptySince ?: return
        if (nowMillis() - since >= idleDisbandMillis) disband(DisbandReason.IDLE_TIMEOUT)
    }

    private fun onReleaseUnclaimedSeat(seat: Seat) {
        val slot = slots.getOrNull(seat.index) ?: return
        // The owner reconnected (occupant set), the game started (seats then stay reclaimable for
        // its whole duration), or the seat was already reassigned — all mean this release is stale.
        if (slot.occupant != null || slot.ownerToken == null || phase != RoomPhase.LOBBY) return
        // Mirrors the live grace expiry: a lobby whose creator is confirmed gone can never start.
        if (slot.ownerToken == creatorToken) {
            disband(DisbandReason.CREATOR_DISBANDED)
            return
        }
        clearSlot(slot)
        broadcastLobby()
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private fun seat(connection: PlayerConnection, slot: Slot, name: String?) {
        slot.occupant = connection
        slot.name = name
        slot.ready = false
        slot.ownerToken = connection.sessionToken
        connection.roomId = gameId
        connection.seat = slot.seat
        sessionRegistry.bind(connection.sessionToken, gameId, slot.seat)
    }

    private fun clearSlot(slot: Slot) {
        slot.occupant?.let { detachConnection(it) }
        // Forget the session→seat binding so a later reconnect with this token starts fresh instead
        // of being re-seated into (or evicting whoever now holds) a seat it no longer owns.
        slot.ownerToken?.let { sessionRegistry.clear(it) }
        slot.ownerToken = null
        slot.occupant = null
        slot.name = null
        slot.ready = false
    }

    private fun detachConnection(connection: PlayerConnection) {
        connection.roomId = null
        connection.seat = null
    }

    private fun slotOf(connection: PlayerConnection): Slot? =
        slots.firstOrNull { it.occupant?.id == connection.id }

    private fun isCreator(connection: PlayerConnection): Boolean =
        connection.sessionToken == creatorToken

    /** True if a slot other than [except] already holds [name], case-insensitively. */
    private fun nameInUse(name: String, except: Slot?): Boolean {
        val lower = name.lowercase()
        return slots.any { it !== except && it.name?.lowercase() == lower }
    }

    private fun validateName(connection: PlayerConnection, raw: String): String? {
        return when (val result = Names.validate(raw)) {
            is Names.Result.Ok -> result.name
            is Names.Result.Rejected -> {
                metrics.rejected(ErrorCode.BAD_NAME)
                reject(connection, ErrorCode.BAD_NAME, "Name not allowed: ${result.reason}")
                null
            }
        }
    }

    private fun creatorSeat(): Seat =
        slots.firstOrNull { it.occupant?.sessionToken == creatorToken }?.seat ?: Seat(0)

    private fun lobbyStateFor(connection: PlayerConnection): LobbyState<C> = LobbyState(
        joinCode = joinCode,
        gameId = gameId,
        config = lobbyConfig,
        seats = slots.map {
            SeatInfo(
                seat = it.seat,
                name = it.name ?: "",
                isBot = it.isBot,
                ready = it.ready,
                connected = it.occupant?.connected == true,
            )
        },
        creatorSeat = creatorSeat(),
        yourSeat = slotOf(connection)?.seat,
        phase = phase,
    )

    private fun broadcastLobby() {
        for (slot in slots) {
            slot.occupant?.let { deliver(it, lobbyStateFor(it)) }
        }
    }

    private fun broadcastSeatStatus(seat: Seat, status: OccupancyStatus) =
        broadcastAll(SeatStatus(seat, status))

    private fun broadcastAll(message: ServerMessage) {
        for (slot in slots) slot.occupant?.let { deliver(it, message) }
    }

    private fun replayView(connection: PlayerConnection, seat: Seat) {
        val update = latestViews[seat] ?: return
        val deadline = currentTurnDeadline
        val remaining = if (currentActor == seat && deadline != null) {
            (deadline - nowMillis()).coerceAtLeast(0)
        } else {
            null
        }
        deliver(connection, update.copy(turnRemainingMillis = remaining))
    }

    /**
     * [SeatStatus] is a transient broadcast: a client that was itself disconnected when another
     * seat's substitution or reclaim was announced resumes with a stale occupancy picture, and
     * nothing else in the resume flow corrects it — the reclaimed player's name stays "(bot)" on
     * that client forever. So every reconnector is refreshed with the CURRENT status of every
     * other human-owned seat (permanent bots were never announced and need no correction).
     */
    private fun refreshOccupancy(reconnector: PlayerConnection, ownSeat: Seat) {
        slots.asSequence()
            .filter { it.seat != ownSeat }
            .filter { it.host?.permanentBot == false }
            .forEach { slot ->
                val held = slot.occupant != null || slot.host?.awaitingReclaim == true
                val status = if (held) OccupancyStatus.HUMAN else OccupancyStatus.BOT_SUBSTITUTE
                deliver(reconnector, SeatStatus(slot.seat, status))
            }
    }

    private fun deliver(connection: PlayerConnection, message: ServerMessage) {
        if (!connection.enqueue(message)) connection.requestClose()
    }

    private fun reject(
        connection: PlayerConnection,
        code: ErrorCode,
        message: String,
        fatal: Boolean = false,
    ) {
        metrics.rejected(code)
        deliver(connection, ErrorMessage(code, message, fatal))
    }

    private fun rejectNotInLobby(connection: PlayerConnection) =
        reject(connection, ErrorCode.NOT_IN_LOBBY, "You are not seated in this room")

    private fun markActivity() {
        emptySince = null
    }

    private fun recomputeEmptiness() {
        val anyConnected = slots.any { it.occupant?.connected == true }
        emptySince = if (anyConnected) null else (emptySince ?: nowMillis())
    }

    private fun disband(reason: DisbandReason) {
        if (closed) return
        broadcastAll(LobbyDisbanded(reason))
        for (slot in slots) {
            slot.occupant?.let { detachConnection(it) }
            slot.ownerToken?.let { sessionRegistry.clear(it) } // don't leave bindings pointing at a dead room
        }
        persistence.delete(gameId)
        close()
    }

    private fun close() {
        closed = true
        driverJob?.cancel()
        commands.close()
        onClosed(this)
    }

    /**
     * Called on graceful server SIGTERM. With durable persistence the room does nothing: its
     * snapshot is already on disk, and a disband broadcast would make every client abandon the very
     * session token it needs to reclaim its seat after the restart. Sockets die with the process
     * and clients reconnect-and-resume. Without persistence, disband as before — the game is lost
     * either way, and clients should be told rather than left retrying.
     */
    fun shutdown() {
        if (persistence.durable) return
        submit(RoomCommand.ForceDisband(DisbandReason.SERVER_SHUTDOWN))
    }

    /** Build the [ResumedState] for a client that reconnected into this room. */
    fun resumedState(): ResumedState = ResumedState(joinCode, phase)

    // --- Persistence --------------------------------------------------------------------------------

    /**
     * Queue the current state for the snapshot writer. Runs after every handled command (cheap: the
     * writer conflates per room). Skipped while PLAYING with no state yet — the previous LOBBY
     * snapshot on disk stays valid until the driver's first StateProduced arrives — and after
     * FINISHED/disband, whose handlers queued the file's deletion instead.
     */
    private fun persist() {
        if (!persistence.durable || closed || phase == RoomPhase.FINISHED) return
        val state = latestState
        if (phase == RoomPhase.PLAYING && state == null) return
        persistence.save(
            RoomSnapshot(
                snapshotVersion = RoomSnapshot.CURRENT_VERSION,
                gameId = gameId,
                joinCode = joinCode,
                creatorToken = creatorToken,
                lobbyConfig = lobbyConfig,
                phase = phase,
                seats = slots.map { RoomSnapshot.SeatSnapshot(it.name, it.isBot, it.ownerToken) },
                stateVersion = stateVersion,
                gameState = state.takeIf { phase == RoomPhase.PLAYING },
                savedAtMillis = nowMillis(),
            ),
        )
    }

    /**
     * Rebuild this room from [snapshot]. Must run before [start] (no actor is consuming yet, so
     * direct mutation is safe; anything launched here queues commands until the actor drains them).
     *
     * Every seat comes back occupant-less, reclaimable by its owner's session token — the ordinary
     * reclaim path. A restored game holds its driver until the first owner reconnects (bot-covering
     * the rest, like any live disconnect); a restored lobby reserves owned seats for the disconnect
     * grace before freeing them. The idle sweep reaps the room if nobody returns at all.
     */
    internal fun restoreFrom(snapshot: RoomSnapshot<S, C>) {
        lobbyConfig = snapshot.lobbyConfig
        phase = snapshot.phase
        stateVersion = snapshot.stateVersion
        for ((slot, seatSnapshot) in slots.zip(snapshot.seats)) {
            slot.name = seatSnapshot.name
            slot.isBot = seatSnapshot.isBot
            slot.ownerToken = seatSnapshot.ownerToken
            seatSnapshot.ownerToken?.let { sessionRegistry.bind(it, gameId, slot.seat) }
        }
        emptySince = nowMillis() // nobody is connected yet; the idle sweep reaps a room no one reclaims
        val state = snapshot.gameState
        if (snapshot.phase == RoomPhase.PLAYING && state != null) {
            restoreGame(state)
        } else if (snapshot.phase == RoomPhase.LOBBY) {
            for (slot in slots) {
                if (slot.ownerToken == null) continue
                scope.launch {
                    delay(config.lobbyDisconnectGraceMillis)
                    submit(RoomCommand.ReleaseUnclaimedSeat(slot.seat))
                }
            }
        }
    }

    private fun restoreGame(state: S) {
        val gameRules = descriptor.rulesFor(lobbyConfig)
        rules = gameRules
        for (slot in slots) {
            val host = SeatHost<V, A>(
                seat = slot.seat,
                bot = bot,
                // Seeded off the state's own evolving seed: deterministic for this restore, though not
                // the sequence the pre-restart process would have produced — bot RNG continuity is
                // not part of the engine's determinism guarantee.
                botRandom = Random(descriptor.botRestoreSeed(state) + slot.seat.index + 1),
                turnTimeout = turnTimeoutMillis.milliseconds,
            )
            host.permanentBot = slot.isBot
            slot.host = host
        }
        metrics.gameStarted()
        // Fan the restored views out through the normal bookkeeping (latestState/latestViews/
        // stateVersion/currentActor) so a reconnecting client is served immediately — but hold the
        // driver until someone actually reclaims a seat (see [awaitingRestoredDriver]).
        onStateProduced(state)
        awaitingRestoredDriver = true
    }

    private fun pickBotNames(count: Int): List<String> = pickBotNames(
        descriptor.botNames,
        slots.mapNotNull { it.name },
        count,
        Random(joinCode.hashCode().toLong()),
    )

    internal companion object {
        const val IDLE_CHECKS_PER_WINDOW = 6L
        const val MIN_IDLE_INTERVAL = 200L
        const val MAX_IDLE_INTERVAL = 60_000L

        /**
         * Pick [count] bot base names from [pool], never one that collides case-insensitively with a
         * [taken] human name — a human "Jack" must not sit at a table with a "Jack (bot)".
         */
        fun pickBotNames(pool: List<String>, taken: Collection<String>, count: Int, random: Random): List<String> {
            val takenLower = taken.map { it.lowercase() }.toSet()
            return pool.filter { it.lowercase() !in takenLower }.shuffled(random).take(count.coerceAtLeast(0))
        }
    }
}
