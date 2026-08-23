// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.testing

import io.github.rotundtapir.cardkit.core.GameRules
import io.github.rotundtapir.cardkit.core.Seat
import kotlin.random.Random

/**
 * Drives [rules] from [initial] until [GameRules.isTerminal], choosing every action through
 * [policy], and returns the terminal state.
 *
 * Unlike `GameDriver` (production: suspend, one [io.github.rotundtapir.cardkit.core.Player] per
 * seat), this is a synchronous test loop with the assertions a rules test wants for free:
 *
 * - every non-terminal state must name an actor, and that actor must have at least one legal
 *   action (a silent stall is a rules bug, not a finished game);
 * - every action [policy] picks must come from that state's own [GameRules.legalActions] — so any
 *   test driving a match doubles as a legality sweep;
 * - the game must end within [maxSteps] (a generous default; a loop that never terminates fails
 *   with the step count rather than hanging the suite).
 *
 * [policy] receives the acting seat's redacted view and its legal actions — decide from the view
 * exactly as a real player would (peeking at hidden state is what this seam prevents).
 * [onState] observes the initial state and every state after an applied action, for tests that
 * assert invariants across the whole trajectory.
 *
 * [construct] covers actions a game cannot enumerate. Not every legal move is drawn from a list:
 * 500's kitty exchange is "discard any 3 of your 13", a combinatorial set its rules deliberately
 * do not expand, so `legalActions` is empty there while the seat still has a move to make. Without
 * this hook such a phase looks exactly like a stalled game. Return null (the default) to keep the
 * strict reading — an empty legal set is then a rules bug, as it is for most games.
 */
fun <State, Action, View> drive(
    rules: GameRules<State, Action, View>,
    initial: State,
    maxSteps: Int = 100_000,
    onState: (State) -> Unit = {},
    construct: (view: View) -> Action? = { null },
    policy: (view: View, legal: List<Action>) -> Action,
): State {
    var state = initial
    onState(state)
    var steps = 0
    while (!rules.isTerminal(state)) {
        check(steps < maxSteps) {
            "Game did not terminate within $maxSteps steps — non-terminating rules or policy"
        }
        val actor: Seat = checkNotNull(rules.currentActor(state)) {
            "Non-terminal state has no actor (rules bug) after $steps steps"
        }
        val legal = rules.legalActions(state, actor)
        val view = rules.view(state, actor)
        val action = if (legal.isEmpty()) {
            // Either a phase whose action is constructed rather than chosen from a list, or a
            // genuine stall. [construct] decides which; its result is not checked against `legal`
            // (there is nothing to check against) — an illegal one fails in `apply` instead.
            checkNotNull(construct(view)) {
                "Actor $actor has no legal actions in a non-terminal state (rules bug, or a " +
                    "constructed-action phase that needs the `construct` hook) after $steps steps"
            }
        } else {
            policy(view, legal).also { chosen ->
                check(chosen in legal) {
                    "Policy chose $chosen for $actor, which is not among that seat's " +
                        "${legal.size} legal actions"
                }
            }
        }
        state = rules.apply(state, actor, action)
        onState(state)
        steps++
    }
    return state
}

/**
 * Drives a full match with uniformly random legal moves from [rng] — the standard smoke/legality
 * sweep. Deterministic for a seeded [rng]; returns the terminal state.
 */
fun <State, Action, View> driveRandomly(
    rules: GameRules<State, Action, View>,
    initial: State,
    rng: Random,
    maxSteps: Int = 100_000,
    onState: (State) -> Unit = {},
    construct: (view: View) -> Action? = { null },
): State = drive(rules, initial, maxSteps, onState, construct) { _, legal -> legal.random(rng) }
