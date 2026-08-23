// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.testing

import io.github.rotundtapir.cardkit.core.GameRules
import io.github.rotundtapir.cardkit.core.Seat
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A minimal two-seat race to [target]: the actor alternates by parity of the running total, each
 * turn adds 1 or 2. Small enough to reason about, rich enough to exercise every [drive] assertion.
 */
private class CountingRules(private val target: Int) : GameRules<Int, Int, Int> {
    override fun currentActor(state: Int): Seat? = if (state >= target) null else Seat(state % 2)
    override fun isTerminal(state: Int): Boolean = state >= target
    override fun view(state: Int, seat: Seat): Int = state
    override fun legalActions(state: Int, seat: Seat): List<Int> = listOf(1, 2)
    override fun apply(state: Int, seat: Seat, action: Int): Int = state + action
}

/**
 * A rules set with a *constructed*-action phase: on an odd state nothing is enumerable (as 500's
 * kitty exchange is "any 3 of your 13"), so the driver must ask the `construct` hook instead of
 * concluding the game has stalled.
 */
private class ConstructedActionRules(private val target: Int) : GameRules<Int, Int, Int> {
    override fun currentActor(state: Int): Seat? = if (state >= target) null else Seat(0)
    override fun isTerminal(state: Int): Boolean = state >= target
    override fun view(state: Int, seat: Seat): Int = state
    override fun legalActions(state: Int, seat: Seat): List<Int> =
        if (state % 2 == 0) listOf(1) else emptyList()
    override fun apply(state: Int, seat: Seat, action: Int): Int = state + action
}

class DriveTest {

    @Test
    fun `a constructed-action phase is driven by the construct hook, not treated as a stall`() {
        var constructed = 0
        val final = drive(
            ConstructedActionRules(target = 6),
            initial = 0,
            construct = { constructed++; 1 },
        ) { _, legal -> legal.first() }
        assertTrue(final >= 6)
        assertTrue(constructed > 0, "the odd states have no enumerable action, so the hook must run")
    }

    @Test
    fun `without the hook an empty legal set is still reported as a rules bug`() {
        val e = assertFailsWith<IllegalStateException> {
            drive(ConstructedActionRules(target = 6), initial = 0) { _, legal -> legal.first() }
        }
        assertTrue("no legal actions" in e.message.orEmpty(), e.message.orEmpty())
    }


    @Test
    fun `drives to the terminal state and observes every step`() {
        val seen = mutableListOf<Int>()
        val final = drive(CountingRules(target = 5), initial = 0, onState = seen::add) { _, legal -> legal.first() }
        assertTrue(final >= 5)
        assertEquals(0, seen.first())
        assertEquals(final, seen.last())
        assertEquals(seen.sorted(), seen, "state should only ever advance")
    }

    @Test
    fun `an illegal policy choice fails with the actor and the choice`() {
        val e = assertFailsWith<IllegalStateException> {
            drive(CountingRules(target = 5), initial = 0) { _, _ -> 99 }
        }
        assertTrue("99" in e.message.orEmpty())
    }

    @Test
    fun `a non-terminating game fails at maxSteps instead of hanging`() {
        val stuck = object : GameRules<Int, Int, Int> by CountingRules(target = 5) {
            override fun apply(state: Int, seat: Seat, action: Int): Int = state // never advances
            override fun isTerminal(state: Int): Boolean = false
        }
        val e = assertFailsWith<IllegalStateException> {
            drive(stuck, initial = 0, maxSteps = 50) { _, legal -> legal.first() }
        }
        assertTrue("50" in e.message.orEmpty())
    }

    @Test
    fun `driveRandomly is deterministic for a seeded rng`() {
        val a = mutableListOf<Int>()
        val b = mutableListOf<Int>()
        driveRandomly(CountingRules(target = 100), initial = 0, rng = Random(7), onState = a::add)
        driveRandomly(CountingRules(target = 100), initial = 0, rng = Random(7), onState = b::add)
        assertEquals(a, b)
    }

    @Test
    fun `firstSeedWhere returns the first match and reports the range on failure`() {
        assertEquals(4L, firstSeedWhere(0L..10L) { it * it > 10 })
        val e = assertFailsWith<IllegalStateException> { firstSeedWhere(0L..3L) { false } }
        assertTrue("0..3" in e.message.orEmpty())
    }

    @Test
    fun `seats enumerates the table in order`() {
        assertEquals(listOf(Seat(0), Seat(1), Seat(2), Seat(3)), seats(4))
    }
}
