// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.ui.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.rotundtapir.cardkit.ui.SuitText
import io.github.rotundtapir.cardkit.ui.felt.CardSurfaceWhite
import io.github.rotundtapir.cardkit.ui.felt.InkOnCardSurface

// ------------------------------------------------------------------------------------------------
// The "card face" reading surface, shared by tutorial primer/epilogue pages and rules dialogs.
//
// These dialogs are prose to READ, so they sit on a fixed card-white face in BOTH theme modes —
// like the playing cards and the tutorial bubble — with fixed inks (the felt conventions rule:
// fixed surface ⇒ fixed ink). This also keeps SuitText's black ♠♣ glyphs legible, which vanish on
// the dark theme's dialog surface on web.
// ------------------------------------------------------------------------------------------------

/** Near-black body ink on the card face, softer than pure black against the bright ground. */
private val ReaderInk = Color(0xFF1F2A20)

/** A titled page of tutorial prose, shown in the paged card dialogs before and after the hand. */
data class TutorialPage(val title: String, val body: String)

/** A dialog styled as a large card face: fixed white, rounded, floating above the felt. */
@Composable
fun CardFaceDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = CardSurfaceWhite,
            contentColor = ReaderInk,
            shadowElevation = 12.dp,
            modifier = modifier
                .widthIn(max = 520.dp)
                .let { if (testTag != null) it.testTag(testTag) else it },
        ) {
            Column(content = content)
        }
    }
}

/** A heading in the fixed green ink — the card face's accent color in both theme modes. */
@Composable
fun ReaderTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = InkOnCardSurface,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/** A text button whose ink is pinned to the card face (theme `primary` washes out in dark mode). */
@Composable
fun ReaderTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = InkOnCardSurface),
        modifier = modifier,
    ) {
        Text(label, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium)
    }
}

/** One dot per page, the current one filled solid. */
@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        color = if (index == current) InkOnCardSurface else InkOnCardSurface.copy(alpha = 0.25f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/**
 * A paged card-face dialog over [pages] with dot progress and Back/Next navigation. Used for a
 * tutorial primer (dismissable, finishing deals the hand) and an epilogue (not dismissable,
 * finishing exits to home).
 *
 * [onDismiss] is offered only on the first page — later pages show "Back", which walks the pager.
 * [dismissLabel] names that first-page action: it defaults to "Cancel" for the usual
 * abandon-the-flow case, but a caller that returns somewhere specific (a lesson picker, say)
 * should say so, since a button labelled "Cancel" that navigates rather than cancels misleads.
 *
 * [lastPageTag] tags the dialog surface only on the final page, which is
 * how instrumented tests recognise the completion page. [narrationUriFor] resolves a page body to
 * its narration clip URI (see [NarrateEffect]); the default keeps the pager silent.
 */
@Composable
fun TutorialPagesDialog(
    pages: List<TutorialPage>,
    nextTag: String,
    finishLabel: String,
    finishTag: String,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    dismissLabel: String = "Cancel",
    lastPageTag: String? = null,
    uniformBodyHeight: Boolean = false,
    narration: NarrationState? = null,
    narrationUriFor: (String) -> String? = { null },
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    // A saveable index can outlive its list: if a restore pairs an old index with a shorter page
    // list, clamp instead of throwing on the first frame.
    val current = page.coerceIn(0, pages.lastIndex)
    val onLastPage = current == pages.lastIndex
    NarrateEffect(narration, pages[current].body, narrationUriFor)
    CardFaceDialog(
        onDismissRequest = { onDismiss?.invoke() },
        modifier = modifier,
        testTag = lastPageTag?.takeIf { onLastPage },
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(top = 22.dp, bottom = 8.dp)) {
            // Stable geometry across pages so Next/Back never move under a reader's thumb: when
            // uniformBodyHeight is set, every page is measured at the real width and the body takes
            // the tallest — nothing clips on narrow phones or large font scales, and the chrome
            // never jumps. Otherwise a simple minimum height.
            // The body takes what the window leaves after the button row and scrolls if the page
            // is taller — on a phone in landscape (~360dp tall) a primer page otherwise pushes
            // Next/Cancel below the window, and nothing on screen leads anywhere.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (uniformBodyHeight) {
                    TallestPageBody(pages, current)
                } else {
                    Column(Modifier.heightIn(min = 180.dp)) { PageBody(pages[current]) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                PageDots(pages.size, current)
                if (narration != null) {
                    NarrationToggle(narration, compact = true, tint = InkOnCardSurface)
                }
                Spacer(Modifier.weight(1f))
                when {
                    current > 0 -> ReaderTextButton("Back", onClick = { page = current - 1 })
                    onDismiss != null -> ReaderTextButton(dismissLabel, onClick = onDismiss)
                }
                if (onLastPage) {
                    ReaderTextButton(
                        finishLabel,
                        onClick = onFinish,
                        emphasized = true,
                        modifier = Modifier.testTag(finishTag),
                    )
                } else {
                    ReaderTextButton(
                        "Next",
                        onClick = { page = current + 1 },
                        emphasized = true,
                        modifier = Modifier.testTag(nextTag),
                    )
                }
            }
        }
    }
}

/** One page's title + body, shared by the live page and the measurement pass. */
@Composable
private fun PageBody(page: TutorialPage, modifier: Modifier = Modifier) {
    Column(modifier) {
        ReaderTitle(page.title)
        Spacer(Modifier.height(10.dp))
        SuitText(page.body, fontSize = 16.sp, lineHeight = 24.sp)
    }
}

/**
 * Shows [current]'s body inside a slot sized to the TALLEST of [pages] at the real width: the
 * pager's chrome (dots, toggles, buttons) never moves between pages, and no page ever clips —
 * regardless of screen width or the user's font scale. The pager places it inside a scroller, so
 * a page taller than the window scrolls rather than clipping.
 */
@Composable
fun TallestPageBody(pages: List<TutorialPage>, current: Int, modifier: Modifier = Modifier) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = constraints.maxWidth, minHeight = 0)
        val tallest = pages.mapIndexed { index, page ->
            subcompose("measure-$index") { PageBody(page) }
                .maxOf { it.measure(loose).height }
        }.max().coerceAtMost(constraints.maxHeight)
        val placeables = subcompose("current") { PageBody(pages[current]) }
            .map { it.measure(loose) }
        layout(constraints.maxWidth, tallest) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}
