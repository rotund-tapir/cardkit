// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.server

import io.github.rotundtapir.cardkit.core.Player
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Strategy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration

/**
 * The server-side [Player] for one seat — the exact mirror of an app's `ChannelPlayer`, but backed
 * by the network. The [io.github.rotundtapir.cardkit.core.GameDriver] calls [decide] on the actor for
 * each turn; this either plays the bot (empty seat, or a human who dropped/idled) or prompts the
 * human and waits for their action.
 *
 * Bot substitution and seat reclaim fall out of the driver loop for free: [occupant] is read at the
 * start of every [decide], so a disconnect makes the next turn bot-played and a reconnect (which
 * sets [occupant] back) makes the following turn human again. The reclaim happens at the turn
 * boundary because that is when [decide] next reads [occupant].
 */
class SeatHost<V : Any, A : Any>(
    val seat: Seat,
    private val bot: Strategy<V, A>,
    private val botRandom: Random,
    private val turnTimeout: Duration,
) : Player<V, A> {

    /** The connection currently playing this seat, or null for a bot (empty seat or dropped human). */
    @Volatile
    var occupant: PlayerConnection? = null

    /** True for a seat that was empty at game start — a bot for the whole game, never reclaimable. */
    @Volatile
    var permanentBot: Boolean = false

    // Capacity 1, drained by [beginTurn] at each turn boundary (see below). The buffer means submit()
    // is a non-suspending trySend that never depends on a receiver being parked at that exact instant
    // (a rendezvous channel does not reliably hand off to a select-registered receiver), and the
    // draining is what keeps it safe: an action left over from a previous turn — e.g. one that raced
    // ahead of the room's state bookkeeping after a timeout already ran the bot — is discarded before
    // the next turn waits, so it can never be consumed as the answer to a *different* turn (the
    // room-wedging bug a plain capacity-1 buffer used to allow).
    private val responses = Channel<A>(capacity = 1)

    // Wakes a parked decide() early when the occupant drops, so the table doesn't freeze for the
    // whole turn timeout waiting on someone who has already left. CONFLATED: a spare signal from a
    // previous turn just coalesces and is dropped at the next [beginTurn].
    private val interrupts = Channel<Unit>(capacity = Channel.CONFLATED)

    /**
     * Discard anything buffered from earlier turns — stale submits, a spent interrupt.
     *
     * The room calls this as it opens a new turn, *before* it hands out that turn's views. Draining
     * here rather than when [decide] is entered is load-bearing: the driver only queues its state for
     * the room, so the room can fan the new view out — and a quick client can answer it — before the
     * driver actually reaches [decide]. Draining on entry therefore threw away perfectly good
     * actions, and since the room had already recorded the move as accepted, the client's resend was
     * deduplicated into silence and the player's turn appeared to do nothing until it timed out.
     *
     * Once a turn is open, only that turn's actions can arrive: the room validates `stateVersion` and
     * the acting seat before ever calling [submit].
     */
    fun beginTurn() {
        drain(responses)
        drain(interrupts)
    }

    /**
     * True while the seat's human has dropped but the room is holding it through the in-game
     * disconnect grace: [decide] parks instead of falling straight to the bot, so a reconnect
     * inside the grace resumes the turn as if nothing happened. Cleared by reclaim (occupant set)
     * or by grace expiry ([io.github.rotundtapir.cardkit.server.Room] then interrupts, and the
     * bot covers from there).
     */
    @Volatile
    var awaitingReclaim: Boolean = false

    override suspend fun decide(view: V): A {
        if (permanentBot) return bot.decide(view, botRandom)
        val conn = occupant
        val absent = conn == null || !conn.connected
        if (absent && !awaitingReclaim) {
            return bot.decide(view, botRandom)
        }
        // The client already has this view (the room fanned it out after the previous action); it
        // carries whose turn it is plus the legal actions, so it is the turn prompt. Wait for the
        // action, falling back to the bot for this one turn if the human runs out the clock OR drops
        // mid-turn ([interrupt]). A timeout does NOT surrender the seat: the occupant stays connected
        // and is prompted again on their next turn. Only an actual socket drop evicts, via the room's
        // Disconnected handling, with reclaim on reconnect.
        val chosen: A? = withTimeoutOrNull(turnTimeout) {
            select {
                responses.onReceive { it }
                interrupts.onReceive { null } // occupant dropped — hand this turn to the bot now
            }
        }
        return chosen ?: bot.decide(view, botRandom)
    }

    /** Nudge a parked [decide] to fall back to the bot immediately (the occupant just disconnected). */
    fun interrupt() {
        interrupts.trySend(Unit)
    }

    /** Discard everything currently buffered in [channel]. */
    private fun <T> drain(channel: Channel<T>) {
        var next = channel.tryReceive()
        while (next.isSuccess) next = channel.tryReceive()
    }

    /**
     * Hand a validated action to [decide]. Returns false only if the (capacity-1) buffer already
     * holds an un-consumed action — the network analogue of `trySubmit`. Anything buffered is
     * discarded at the next [decide] entry, so it can never answer a later turn.
     */
    fun submit(action: A): Boolean = responses.trySend(action).isSuccess
}
