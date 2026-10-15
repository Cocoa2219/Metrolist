package com.metrolist.wear

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.pager.PagerState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Degrees on the right edge (0° = 3 o'clock, clockwise), measured off Spotify's watch app. Items sit in a row
// that stays centred while home's track grows, so the media dots shift up to make room.
private const val MEDIA_PAGES = 2
// Centre-to-centre distance between dots; also the gap between home's thumb and the rest of its track.
private const val SPACING = 5.5f
private const val TRACK_FULL = 24.5f
private const val CENTER_COLLAPSED = 0f
private const val CENTER_EXPANDED = -5f
private const val HIDE_AFTER_MS = 1_500L
private val Selected = Color.White
private val Unselected = Color.White.copy(alpha = 0.35f)

/**
 * One indicator for both axes, as in Spotify: a dot per media page and, for home, a pill that stretches
 * into that list's scrollbar as the pager reaches it. On the media pages it hides once things are still.
 */
@Composable
fun PagerScrollIndicator(
    pagerState: PagerState,
    listState: TransformingLazyColumnState,
    modifier: Modifier = Modifier,
) {
    val homePage = MEDIA_PAGES
    val active by remember {
        derivedStateOf {
            pagerState.currentPageOffsetFraction != 0f || pagerState.currentPage == homePage || listState.isScrollInProgress
        }
    }
    var visible by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active) {
            visible = true
        } else {
            delay(HIDE_AFTER_MS)
            visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "indicatorAlpha")

    Canvas(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
        val towardHome = (position - (homePage - 1)).coerceIn(0f, 1f)
        val radius = size.minDimension / 2 - 8.dp.toPx()
        val width = 7.dp.toPx()

        val trackLength = TRACK_FULL * towardHome
        val groupStart = CENTER_COLLAPSED + (CENTER_EXPANDED - CENTER_COLLAPSED) * towardHome -
            (SPACING * MEDIA_PAGES + trackLength) / 2
        val dotAt = List(MEDIA_PAGES) { groupStart + it * SPACING }
        val trackStart = groupStart + SPACING * MEDIA_PAGES
        val trackEnd = trackStart + trackLength

        dotAt.forEach { drawSegment(it, it, Unselected, radius, width) }

        // The current page is a white dot that moves like a worm between artwork and player: its leading edge
        // reaches the next dot in the first half of the swipe, then its trailing edge catches up and it's a dot again.
        if (position < MEDIA_PAGES - 1) {
            val from = position.toInt().coerceIn(0, MEDIA_PAGES - 2)
            val progress = position - from
            val lead = (progress * 2f).coerceAtMost(1f)
            val trail = (progress * 2f - 1f).coerceAtLeast(0f)
            val step = dotAt[from + 1] - dotAt[from]
            drawSegment(dotAt[from] + step * trail, dotAt[from] + step * lead, Selected, radius, width)
        } else {
            // Toward home the player's white dot hands over to the scroll thumb.
            drawSegment(dotAt.last(), dotAt.last(), Selected.copy(alpha = 1f - towardHome), radius, width)
        }

        // Home's track; on home the thumb is its own object (M3's gapped style). As in Spotify the thumb uses the
        // whole track, and the grey piece on either side lives in whatever space the thumb leaves (minus a gap):
        // nothing when flush, then a dot that grows, then a segment. No thumb until the list has laid out and can scroll.
        val thumb = listState.thumb()
        if (thumb == null || towardHome == 0f) {
            drawSegment(trackStart, trackEnd, Unselected, radius, width)
        } else {
            val (start, length) = thumb
            val thumbLength = trackLength * length
            val thumbStart = trackStart + (trackLength - thumbLength) * start
            val thumbEnd = thumbStart + thumbLength
            val dotDegrees = (width / radius * 180f / PI).toFloat()
            drawEmergingPiece(trackStart, thumbStart - SPACING, anchorAtStart = true, dotDegrees, radius, width)
            drawSegment(thumbStart, thumbEnd, Selected.copy(alpha = towardHome), radius, width)
            drawEmergingPiece(thumbEnd + SPACING, trackEnd, anchorAtStart = false, dotDegrees, radius, width)
        }
    }
}

/**
 * A grey track piece from [from] to [to] that may not fit yet: with negative room it's a dot scaled by how much of
 * a dot's diameter is available, pinned to the track end ([anchorAtStart] picks which), and gone below that.
 */
private fun DrawScope.drawEmergingPiece(
    from: Float,
    to: Float,
    anchorAtStart: Boolean,
    dotDegrees: Float,
    radius: Float,
    width: Float,
) {
    val room = to - from
    if (room >= 0f) {
        drawSegment(from, to, Unselected, radius, width)
        return
    }
    val scale = ((room + dotDegrees) / dotDegrees).coerceIn(0f, 1f)
    if (scale > 0f) drawCircle(Unselected, width / 2 * scale, pointAt(if (anchorAtStart) from else to, radius))
}

/** An arc from [from] to [to] with round caps, or a dot when it has no length. */
private fun DrawScope.drawSegment(
    from: Float,
    to: Float,
    color: Color,
    radius: Float,
    width: Float,
) {
    if (to - from < 0.1f) {
        drawCircle(color, width / 2, pointAt(from, radius))
    } else {
        drawEdgeArc(from, to, color, radius, width)
    }
}

/** Thumb position (0..1 of the free track) and length (fraction of the track) from visible items; null before layout. */
private fun TransformingLazyColumnState.thumb(): Pair<Float, Float>? {
    val info = layoutInfo
    val visible = info.visibleItems
    if (info.totalItemsCount == 0 || visible.isEmpty()) return null
    // A list that fits (e.g. home still loading) has nothing to scroll, so a full-length thumb would only be noise.
    if (!canScrollForward && !canScrollBackward) return null
    val length = (visible.size.toFloat() / info.totalItemsCount).coerceIn(0.15f, 1f)
    val first = visible.first()
    val scrolledItems = first.index + (-first.offset.toFloat() / first.measuredHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
    val start =
        when {
            !canScrollBackward -> 0f
            !canScrollForward -> 1f
            else -> (scrolledItems / (info.totalItemsCount - visible.size).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    return start to length
}

private fun DrawScope.pointAt(
    degrees: Float,
    radius: Float,
): Offset {
    val radians = degrees * PI / 180
    return Offset(center.x + radius * cos(radians).toFloat(), center.y + radius * sin(radians).toFloat())
}

private fun DrawScope.drawEdgeArc(
    from: Float,
    to: Float,
    color: Color,
    radius: Float,
    width: Float,
) {
    drawArc(
        color = color,
        startAngle = from,
        sweepAngle = to - from,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width, cap = StrokeCap.Round),
    )
}
