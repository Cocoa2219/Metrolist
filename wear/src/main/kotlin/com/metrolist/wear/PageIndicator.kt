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

// Degrees on the right edge (0° = 3 o'clock, clockwise), measured off Spotify's watch app. The group (a dot per
// media page, then home's track) stays centred while the track grows, so the dots move up to make room.
private const val MEDIA_PAGES = 2
private const val DOT_SPACING = 5.5f
private const val TRACK_GAP = 5.5f
private const val TRACK_SHORT = 7.5f
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

        val trackLength = TRACK_SHORT + (TRACK_FULL - TRACK_SHORT) * towardHome
        val groupSpan = DOT_SPACING * (MEDIA_PAGES - 1) + TRACK_GAP + trackLength
        val groupStart = CENTER_COLLAPSED + (CENTER_EXPANDED - CENTER_COLLAPSED) * towardHome - groupSpan / 2
        repeat(MEDIA_PAGES) { index ->
            val color = if (pagerState.currentPage == index) Selected else Unselected
            drawCircle(color, width / 2, pointAt(groupStart + index * DOT_SPACING, radius))
        }
        val trackStart = groupStart + DOT_SPACING * (MEDIA_PAGES - 1) + TRACK_GAP
        drawEdgeArc(trackStart, trackStart + trackLength, Unselected, radius, width)
        // No thumb until home's list has laid out; guessing "all visible" would flash the whole track white.
        val thumb = listState.thumb()
        if (towardHome > 0f && thumb != null) {
            val (start, length) = thumb
            val from = trackStart + start * trackLength * (1f - length)
            val span = trackLength
            drawEdgeArc(from, from + span * length, Selected.copy(alpha = towardHome), radius, width)
        }
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
