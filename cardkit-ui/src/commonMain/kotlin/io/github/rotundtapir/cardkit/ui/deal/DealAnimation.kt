// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.ui.deal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.ui.CardBack
import io.github.rotundtapir.cardkit.ui.PlayingCard
import io.github.rotundtapir.cardkit.ui.SoundEffect
import io.github.rotundtapir.cardkit.ui.settings.AnimationSpeed
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The dealing animation for a new hand: card backs fly from a deck in the centre of the felt to
 * each destination in the game's true packet order (supplied as a [DealPacket] schedule — e.g.
 * 500's 3-4-3 to each seat with a kitty card after each round). Opponents' piles grow in the
 * opponents row, centre piles grow on the felt, and the human's cards accumulate face down at the
 * bottom of the screen, then flip face up (with a small left-to-right stagger) when the deal
 * completes.
 *
 * Everything here is presentational: the engine has already dealt. The game releases the first
 * actor on the deal-done signal its screen raises when [runDealAnimation] finishes; the pacing
 * gates' `dealPauseMillis` (derived from [dealTimings]) only scales the deadlock backstop for the
 * case where the signal never comes. At [AnimationSpeed.OFF] none of this runs and
 * [DealAnimationState.stage] stays [DealStage.DONE].
 */
enum class DealStage { SHUFFLING, DEALING, FLIPPING, DONE }

/** A destination a dealt card can fly to. Also used as the anchor key for that destination. */
sealed interface DealTarget {
    /** A player's pile (the human hand row or an opponent pile). */
    data class SeatPile(val seat: Seat) : DealTarget

    /** A pile on the felt that is not a seat — a kitty, a stock, a crib — identified by [key]. */
    data class Center(val key: String) : DealTarget
}

/** One flight of the deal: [cards] card backs flying together to [target]. */
data class DealPacket(val target: DealTarget, val cards: Int)

/** Anchor key for the deck the cards fly *from*. */
data object DeckAnchor

class DealAnimationState {
    var stage by mutableStateOf(DealStage.DONE)

    /**
     * Where deal sounds go: called with [SoundEffect.SHUFFLE] once per riffle and
     * [SoundEffect.CARD_SLIDE] as each packet lands. Null (the default) means silent — the
     * integration layer wires this to a `SoundManager`. Kept as a hook so this file stays free
     * of any audio implementation.
     */
    var soundHook: ((SoundEffect) -> Unit)? = null

    /** Cards landed so far, per destination. */
    val counts = mutableStateMapOf<DealTarget, Int>()

    /** While shuffling: true when the deck's two halves are pulled apart mid-riffle. */
    var shuffleSplit by mutableStateOf(false)

    /** Non-null while exactly one packet is in flight towards this target. */
    var flyingTarget by mutableStateOf<DealTarget?>(null)

    /** How many card backs the in-flight packet contains. */
    var flyingCount by mutableIntStateOf(1)

    /** Centre of the in-flight card, in root coordinates. */
    val flyingPos = Animatable(Offset.Zero, Offset.VectorConverter)

    /** Destination/deck centres in root coordinates, reported by [dealAnchor]. */
    val anchors = mutableStateMapOf<Any, Offset>()

    /** Root offset of the overlay Box the flying card is drawn in. */
    var overlayOrigin by mutableStateOf(Offset.Zero)

    val dealing: Boolean get() = stage != DealStage.DONE

    fun dealtTo(seat: Seat): Int = countFor(DealTarget.SeatPile(seat))

    /** Cards landed on [target] so far this deal (0 before the first packet arrives). */
    fun countFor(target: DealTarget): Int = counts[target] ?: 0
}

/** Reports this composable's centre (in root coordinates) as the anchor for [key]. */
fun Modifier.dealAnchor(state: DealAnimationState, key: Any): Modifier =
    onGloballyPositioned { coords ->
        state.anchors[key] =
            coords.positionInRoot() + Offset(coords.size.width / 2f, coords.size.height / 2f)
    }

/**
 * Per-speed budgets. The flights self-correct against a deadline (frame quantisation would
 * otherwise accumulate). Packet dealing keeps the flight count small, so each flight is long
 * enough to actually watch — ~180ms at Normal, ~280ms at Slow for 500's 15-packet deal. The
 * pacing gates derive their deadlock-backstop hold from these values, so retuning here needs no
 * second edit.
 */
data class DealTimings(
    val shuffleMillis: Long,
    val flyBudgetMillis: Long,
    val flipMillis: Int,
    val flipStaggerMillis: Int,
) {
    /**
     * The flip phase's full duration: the base flip plus [FlippingCard]'s per-index stagger across
     * the human's [handSize] cards, plus a little slack.
     */
    fun flipTotalMillis(handSize: Int): Long = flipMillis + flipStaggerMillis * (handSize - 1L) + FLIP_SLACK_MILLIS
}

private const val FLIP_SLACK_MILLIS = 30L

fun dealTimings(speed: AnimationSpeed): DealTimings = when (speed) {
    AnimationSpeed.SLOW -> DealTimings(shuffleMillis = 1_600, flyBudgetMillis = 4_200, flipMillis = 300, flipStaggerMillis = 40)
    AnimationSpeed.FAST -> DealTimings(shuffleMillis = 400, flyBudgetMillis = 1_100, flipMillis = 140, flipStaggerMillis = 15)
    else -> DealTimings(shuffleMillis = 900, flyBudgetMillis = 2_800, flipMillis = 240, flipStaggerMillis = 28)
}

/** Flights shorter than this read as teleports anyway; skip the animation and just land the packet. */
private const val MIN_FLIGHT_MILLIS = 8

/** Fraction of each flight slot spent moving; the rest is a beat between packets. */
private const val FLIGHT_FRACTION = 0.8f

/**
 * Drives one full deal: a riffle-shuffle, then one flight per [schedule] packet in order, then the
 * face-up flip of the human's [handSize] cards. The game builds the schedule in its rules' true
 * deal order (e.g. 500: a packet of 3 to each seat starting left of the dealer then one card to
 * the kitty, then 4s + kitty, then 3s + kitty). Suspends until the flip has finished; always
 * leaves the state at [DealStage.DONE] even if cancelled mid-deal.
 */
suspend fun runDealAnimation(
    state: DealAnimationState,
    schedule: List<DealPacket>,
    timings: DealTimings,
    handSize: Int,
) {
    val totalFlights = schedule.size
    try {
        state.counts.clear()
        // Riffle the deck a few times before the first packet flies.
        state.stage = DealStage.SHUFFLING
        val riffles = 3
        repeat(riffles) {
            state.soundHook?.invoke(SoundEffect.SHUFFLE)
            state.shuffleSplit = true
            delay(timings.shuffleMillis / (riffles * 2L))
            state.shuffleSplit = false
            delay(timings.shuffleMillis / (riffles * 2L))
        }
        state.stage = DealStage.DEALING
        val dealStart = TimeSource.Monotonic.markNow()
        var flown = 0
        for (packet in schedule) {
            val elapsed = dealStart.elapsedNow().inWholeMilliseconds
            val remaining = timings.flyBudgetMillis - elapsed
            val slot = (remaining / (totalFlights - flown)).toInt()
            state.flyPacket(packet.target, packet.cards, slot)
            flown++
        }
        state.stage = DealStage.FLIPPING
        delay(timings.flipTotalMillis(handSize))
    } finally {
        state.flyingTarget = null
        state.stage = DealStage.DONE
    }
}

private suspend fun DealAnimationState.flyPacket(target: DealTarget, cards: Int, slotMillis: Int) {
    val from = awaitAnchor(DeckAnchor)
    val to = awaitAnchor(target)
    val flightMillis = (slotMillis * FLIGHT_FRACTION).toInt()
    if (from != null && to != null && flightMillis >= MIN_FLIGHT_MILLIS) {
        flyingPos.snapTo(from)
        flyingCount = cards
        flyingTarget = target
        flyingPos.animateTo(to, tween(flightMillis, easing = FastOutSlowInEasing))
        flyingTarget = null
        counts[target] = (counts[target] ?: 0) + cards
        soundHook?.invoke(SoundEffect.CARD_SLIDE)
        // A short beat between packets so each delivery registers.
        delay((slotMillis - flightMillis).coerceAtLeast(0).toLong())
    } else {
        counts[target] = (counts[target] ?: 0) + cards
    }
}

/** Waits (briefly) for an anchor to be laid out; null if it never appears, so the deal can't hang. */
private suspend fun DealAnimationState.awaitAnchor(key: Any): Offset? =
    anchors[key] ?: withTimeoutOrNull(500L) {
        snapshotFlow { anchors[key] }.filterNotNull().first()
    }

private val FlyingCardWidth = 44.dp
private val FlyingFanStep = 5.dp

/** The in-flight packet — a small fanned stack of card backs — drawn at root coordinates. */
@Composable
fun FlyingDealCard(state: DealAnimationState, modifier: Modifier = Modifier) {
    if (state.flyingTarget == null) return
    Box(
        modifier.offset {
            val centre = state.flyingPos.value - state.overlayOrigin
            IntOffset(
                (centre.x - FlyingCardWidth.toPx() / 2f).roundToInt(),
                (centre.y - FlyingCardWidth.toPx() * 0.7f).roundToInt(),
            )
        },
    ) {
        repeat(state.flyingCount) { i ->
            Box(Modifier.offset(x = FlyingFanStep * i, y = FlyingFanStep * i / 3)) {
                CardBack(width = FlyingCardWidth)
            }
        }
    }
}

/**
 * The deck the cards fly out of: while shuffling it splits into two half-stacks that riffle back
 * together (driven by [DealAnimationState.shuffleSplit]), and it is the [DeckAnchor] every packet
 * departs from. Games place it at their felt centre for the shuffle/deal stages.
 */
@Composable
fun ShufflingDeck(state: DealAnimationState, modifier: Modifier = Modifier) {
    val split by animateDpAsState(
        targetValue = if (state.shuffleSplit) 30.dp else 0.dp,
        animationSpec = tween(120),
        label = "shuffleSplit",
    )
    val tilt by animateFloatAsState(
        targetValue = if (state.shuffleSplit) 7f else 0f,
        animationSpec = tween(120),
        label = "shuffleTilt",
    )
    // Lambda offset + graphicsLayer keep the riffle in the placement/draw phases:
    // reading `split`/`tilt` during composition would recompose (and the plain
    // offset(x=) overload relayout) every animation frame — visible jank on wasm.
    Box(modifier.dealAnchor(state, DeckAnchor)) {
        Box(
            Modifier
                .offset { IntOffset(-split.roundToPx(), 0) }
                .graphicsLayer { rotationZ = -tilt },
        ) {
            repeat(2) { i ->
                Box(Modifier.offset(x = 1.5.dp * i, y = 1.5.dp * i)) { CardBack(width = 48.dp) }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(split.roundToPx(), 0) }
                .graphicsLayer { rotationZ = tilt },
        ) {
            repeat(2) { i ->
                Box(Modifier.offset(x = 1.5.dp * i, y = 1.5.dp * i)) { CardBack(width = 48.dp) }
            }
        }
    }
}

private val OpponentPileStep = 1.2.dp

/**
 * An opponent's card-back pile. While dealing it thickens as each flown packet lands (a faint
 * outline marks the empty slot before the first card arrives); afterwards it stays a stack whose
 * thickness tracks the cards actually left in that hand, thinning as they are played. Both forms
 * occupy the same footprint so the row never jumps.
 */
@Composable
fun OpponentPile(seat: Seat, state: DealAnimationState, width: Dp, handSize: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width + OpponentPileStep * 4, width * 1.4f + OpponentPileStep * 4)
            .dealAnchor(state, DealTarget.SeatPile(seat)),
    ) {
        val cards = if (state.dealing) state.dealtTo(seat) else handSize
        // One visual layer per two cards or so, capped: 10 cards ≈ 5 layers, thinning as they play.
        val layers = ((cards + 1) / 2).coerceAtMost(5)
        if (layers == 0) {
            Box(Modifier.alpha(0.25f)) { CardBack(width = width) }
        } else {
            repeat(layers) { i ->
                Box(Modifier.offset(x = OpponentPileStep * i, y = OpponentPileStep * i)) {
                    CardBack(width = width)
                }
            }
        }
    }
}

/** Default width for the dealt-hand row; callers with a height budget pass their own. */
val DealHandCardWidth = 64.dp

/**
 * The human's hand area while dealing/flipping: face-down backs accumulate as cards land, then
 * every card flips face up (Y-axis rotation, staggered left to right) revealing the same order
 * the interactive hand will render in. Replaces the action area until the flip completes.
 */
@Composable
fun DealingHandRow(
    cards: List<Card>,
    state: DealAnimationState,
    humanSeat: Seat,
    timings: DealTimings,
    modifier: Modifier = Modifier,
    // Sized by the caller when the screen is short, so the row that lands mid-deal matches the fan
    // it becomes instead of overflowing the layout (500#41).
    cardWidth: Dp = DealHandCardWidth,
    // Fraction of each card left uncovered by its right neighbour. Pass the SAME value the live
    // hand uses (CardHand's `exposure`): the dealt row is replaced by that fan the moment the flip
    // ends, and any difference shows as the whole hand snapping tighter or looser.
    exposure: Float = 0.55f,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxWidth()) {
        Text("You", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        // Same frame as the live fan — full width, centred when it fits, scrolled from the start
        // when it doesn't — so the cards land exactly where they will stay. Without the scroller a
        // wide row overflowed both edges, then jumped left when the interactive hand took over.
        // dealAnchor BEFORE the scroller: it must report the viewport, not the (possibly wider)
        // scrolled content, or a fan that overflows sends the flying cards to a point right of
        // the visible centre. (Ordering nit caught by euchre's session.)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .dealAnchor(state, DealTarget.SeatPile(humanSeat))
                .horizontalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            // Hold the row's height before the first card lands so the layout doesn't jump.
            Spacer(Modifier.height(cardWidth * 1.4f))
            Row(horizontalArrangement = Arrangement.spacedBy(-cardWidth * (1f - exposure))) {
                if (state.stage == DealStage.FLIPPING) {
                    cards.forEachIndexed { i, card ->
                        FlippingCard(card, i, cardWidth, timings)
                    }
                } else {
                    repeat(state.dealtTo(humanSeat)) { CardBack(width = cardWidth) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** One card of the reveal: back rotates 0→90°, then the face (pre-mirrored) carries 90°→180°. */
@Composable
fun FlippingCard(card: Card, index: Int, width: Dp, timings: DealTimings, modifier: Modifier = Modifier) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * timings.flipStaggerMillis.toLong())
        rotation.animateTo(180f, tween(timings.flipMillis, easing = FastOutSlowInEasing))
    }
    // The rotation itself is read only inside graphicsLayer (draw phase); the back/face switch
    // goes through derivedStateOf so each card recomposes exactly once (at 90°) rather than on
    // every frame of the flip — ten cards recomposing per frame stuttered visibly on wasm.
    val showBack by remember { derivedStateOf { rotation.value <= 90f } }
    Box(
        modifier.graphicsLayer {
            rotationY = rotation.value
            cameraDistance = 8f * density
        },
    ) {
        if (showBack) {
            CardBack(width = width)
        } else {
            Box(Modifier.graphicsLayer { rotationY = 180f }) { PlayingCard(card, width = width) }
        }
    }
}
