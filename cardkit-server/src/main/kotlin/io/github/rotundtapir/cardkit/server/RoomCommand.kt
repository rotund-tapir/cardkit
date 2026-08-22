// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.server

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.net.DisbandReason
import io.github.rotundtapir.cardkit.net.Emote

/**
 * Everything that mutates a [Room] arrives as one of these on the room's single command channel, so
 * a lone consumer coroutine serialises all state changes — no locks, no races. Client requests, the
 * driver's state callbacks, disconnects, and timers are all commands.
 *
 * Generic over the game's action ([A]) and state ([S]) types: only the three commands that actually
 * carry game data mention them.
 */
sealed interface RoomCommand<out S, out A> {
    /** A client requests to sit down (join or create). The creator is identified by session token. */
    data class Join(val connection: PlayerConnection, val displayName: String) : RoomCommand<Nothing, Nothing>

    data class Reconnect(val connection: PlayerConnection, val seat: Seat) : RoomCommand<Nothing, Nothing>

    data class SetName(val connection: PlayerConnection, val displayName: String) :
        RoomCommand<Nothing, Nothing>

    data class PickSeat(val connection: PlayerConnection, val seat: Seat) : RoomCommand<Nothing, Nothing>

    data class SetReady(val connection: PlayerConnection, val ready: Boolean) : RoomCommand<Nothing, Nothing>

    data class Configure(
        val connection: PlayerConnection,
        val turnTimeoutSeconds: Int?,
        val idleDisbandMinutes: Int?,
    ) : RoomCommand<Nothing, Nothing>

    data class Start(val connection: PlayerConnection) : RoomCommand<Nothing, Nothing>

    data class Submit<A>(val connection: PlayerConnection, val stateVersion: Int, val action: A) :
        RoomCommand<Nothing, A>

    data class SendEmote(val connection: PlayerConnection, val emote: Emote) : RoomCommand<Nothing, Nothing>
    data class Leave(val connection: PlayerConnection) : RoomCommand<Nothing, Nothing>
    data class Disband(val connection: PlayerConnection) : RoomCommand<Nothing, Nothing>
    data class Rematch(val connection: PlayerConnection) : RoomCommand<Nothing, Nothing>
    data class Disconnected(val connection: PlayerConnection) : RoomCommand<Nothing, Nothing>

    /**
     * The reconnect grace window scheduled by a lobby/post-game [Disconnected] ran out. A no-op if
     * the seat's owner reconnected in the meantime (the slot then holds a different connection).
     */
    /** The in-game disconnect grace for [seat] ran out without a reclaim: the bot takes over. */
    data class GameDisconnectGraceExpired(
        val seat: io.github.rotundtapir.cardkit.core.Seat,
        val generation: Int,
    ) : RoomCommand<Nothing, Nothing>

    data class DisconnectGraceExpired(val connection: PlayerConnection) : RoomCommand<Nothing, Nothing>

    /**
     * The reclaim grace for a seat restored from a snapshot ran out. Frees the seat for new joiners
     * unless its owner reconnected in the meantime (the slot then has an occupant). Lobby-phase
     * restores only — an in-game seat stays reclaimable for the whole game, exactly like a live
     * disconnect.
     */
    data class ReleaseUnclaimedSeat(val seat: Seat) : RoomCommand<Nothing, Nothing>

    /** Fired by the [io.github.rotundtapir.cardkit.core.GameDriver] after every applied action. */
    data class StateProduced<S>(val state: S) : RoomCommand<S, Nothing>

    /** Fired when the driver reaches a terminal state. */
    data class GameFinished<S>(val state: S) : RoomCommand<S, Nothing>

    /** Server-initiated teardown (graceful shutdown), bypassing the creator check. */
    data class ForceDisband(val reason: DisbandReason) : RoomCommand<Nothing, Nothing>

    /** Periodic idle-disband check. */
    data object IdleCheck : RoomCommand<Nothing, Nothing>
}
