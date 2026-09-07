// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.ui.felt

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The dealer button — the white puck that sits in front of whoever deals, as at a real table.
 *
 * Both games had reduced this to a letter glued onto a seat name (" (D)"), which reads as part of
 * the name rather than as a marker and is missed. It is deliberately card-coloured
 * ([CardSurfaceWhite] with [NeutralInkOnCardSurface]) rather than themed: it is an object on the
 * felt, not a label, and so should not flip with the color scheme.
 *
 * Callers should reserve [size] on EVERY seat's row, not only the dealer's. The puck is taller than
 * the name it sits beside and the deal moves one seat per hand, so a row that sizes itself to its
 * contents grows and shrinks once a hand, shoving the table around it.
 *
 * [label] is what sighted players see; [contentDescription] is what a screen reader says, because
 * a bare "D" announced next to a name is meaningless. The glyph is sized off the puck (dividing out
 * the font scale), so the description is the accessible path, not the glyph.
 */
@Composable
fun DealerButton(
    modifier: Modifier = Modifier,
    size: Dp = DealerButtonSize,
    label: String = "D",
    contentDescription: String = "Dealer",
) {
    Surface(
        shape = CircleShape,
        color = CardSurfaceWhite,
        contentColor = NeutralInkOnCardSurface,
        border = BorderStroke(1.dp, NeutralInkOnCardSurface.copy(alpha = 0.45f)),
        modifier = modifier
            .size(size)
            .testTag(DealerButtonTag)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                // Sized off the puck rather than from a type scale, so the compact variant stays
                // legible instead of becoming a smudge.
                fontSize = with(LocalDensity.current) { (size * GLYPH_FRACTION).toSp() },
                lineHeight = with(LocalDensity.current) { (size * GLYPH_FRACTION).toSp() },
            )
        }
    }
}

/**
 * Lets a test find the dealer marker without knowing which seat currently holds it. Namespaced like
 * [io.github.rotundtapir.cardkit.ui.CardTestTagPrefix] so it cannot collide with an app's own tags.
 */
const val DealerButtonTag = "ck:dealerButton"

/** The puck at a normal screen size. */
val DealerButtonSize = 26.dp

/** The puck where height is scarce — a phone in landscape, where seats sit in a side column. */
val DealerButtonSizeCompact = 22.dp

private const val GLYPH_FRACTION = 0.62f
