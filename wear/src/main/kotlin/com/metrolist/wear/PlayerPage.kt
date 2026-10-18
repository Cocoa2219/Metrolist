package com.metrolist.wear

import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.composed
import androidx.media3.common.Player
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.IconToggleButton
import androidx.wear.compose.material3.IconToggleButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

val PlayButtonSize = 56.dp
private val RingGap = 6.dp
private val DotTouchRadius = 16.dp

/**
 * One artwork layer shared by the pager, so its look can follow the scroll position: the providers are
 * read at draw time, keeping per-frame pager motion out of composition.
 */
@Composable
fun ArtworkBackground(
    artworkUri: Uri?,
    dim: () -> Float,
    vignette: () -> Float,
    textScrim: () -> Float,
    blackout: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        CrossfadeArtwork(artworkUri)
        Box(
            Modifier.fillMaxSize().drawWithCache {
                // Round, Spotify-strength edge darkening: clear centre, near black at the bezel. Its radius sits
                // just past the screen edge so there's no visible ring where the gradient ends.
                val edges =
                    Brush.radialGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.95f),
                        radius = size.minDimension * 0.56f,
                    )
                // Bottom shade behind the artwork page's title. It lives here, not on that page, so it never
                // drifts with the page and shows its upper edge.
                val shade =
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        0.75f to Color.Black.copy(alpha = 0.4f),
                        1f to Color.Black.copy(alpha = 0.8f),
                    )
                onDrawBehind {
                    drawRect(Color.Black.copy(alpha = dim()))
                    vignette().takeIf { it > 0f }?.let { drawRect(edges, alpha = it) }
                    textScrim().takeIf { it > 0f }?.let { drawRect(shade, alpha = it) }
                    blackout().takeIf { it > 0f }?.let { drawRect(Color.Black.copy(alpha = it)) }
                }
            },
        )
    }
}

private const val ARTWORK_CROSSFADE_MS = 500

/**
 * Keeps the previous song's art until the next one has loaded, then crossfades, so a song change never flashes
 * black. The load warms Coil's memory cache at full size, which the on-screen (smaller) request is then served from.
 */
@Composable
private fun CrossfadeArtwork(artworkUri: Uri?) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(artworkUri) }
    LaunchedEffect(artworkUri) {
        if (artworkUri != null) context.imageLoader.execute(ImageRequest.Builder(context).data(artworkUri).build())
        shown = artworkUri
    }
    Crossfade(targetState = shown, animationSpec = tween(ARTWORK_CROSSFADE_MS), label = "artwork") { uri ->
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun PlayerPage(
    player: Player?,
    nowPlaying: NowPlaying,
    accent: Color,
    liked: Boolean,
    onLikedChange: (Boolean) -> Unit,
    volume: VolumeState,
    haptics: Haptics,
) {
    val position = rememberPositionMs(player, nowPlaying)
    val duration = nowPlaying.durationMs
    val progress = if (duration > 0) (position.longValue.toFloat() / duration).coerceIn(0f, 1f) else 0f
    var seekTarget by remember { mutableStateOf<Float?>(null) }
    val expand by animateFloatAsState(
        if (seekTarget != null) 1f else 0f,
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "seekExpand",
    )

    BoxWithConstraints(Modifier.fillMaxSize().rotaryVolume(volume, haptics)) {
        val density = LocalDensity.current
        val smallRadius = PlayButtonSize / 2 + RingGap
        val largeRadius = min(maxWidth.value, maxHeight.value).dp / 2 * 0.52f
        val seekGesture =
            Modifier.seekGesture(
                player = player,
                durationMs = duration,
                progress = progress,
                ringRadiusPx = with(density) { smallRadius.toPx() },
                touchRadiusPx = with(density) { DotTouchRadius.toPx() },
                haptics = haptics,
                onTargetChange = { seekTarget = it },
                onSeek = { position.longValue = it },
            )

        Box(Modifier.fillMaxSize().then(seekGesture)) {
            // Controls fade as well as dim, so the pause glyph can't show through the seek time.
            Box(Modifier.graphicsLayer { alpha = 1f - 0.85f * expand }) {
                PlayerControls(player, nowPlaying, accent, liked, onLikedChange, haptics)
            }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = expand }.background(Color.Black.copy(alpha = 0.78f)))
            SeekRing(
                progress = progress,
                target = seekTarget,
                accent = accent,
                radius = lerp(smallRadius.value, largeRadius.value, expand).dp,
                strokeWidth = lerp(4f, 7f, expand).dp,
                dotRadius = lerp(4f, 6f, expand).dp,
                trackAlpha = lerp(0.22f, 0.12f, expand),
            )
            seekTarget?.let { target ->
                Text(
                    formatTime((target * duration).toLong()),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = expand },
                )
            }
        }
    }
}

@Composable
private fun PlayerControls(
    player: Player?,
    nowPlaying: NowPlaying,
    accent: Color,
    liked: Boolean,
    onLikedChange: (Boolean) -> Unit,
    haptics: Haptics,
) {
    Box(Modifier.fillMaxSize()) {
        // Full width on purpose: the marquee then enters and leaves past the round edge of the screen.
        SongChangeTransition(nowPlaying, Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 34.dp)) { song ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    song.title.ifEmpty { stringResource(R.string.nothing_playing) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = MARQUEE_PASSES, initialDelayMillis = 1_500).padding(horizontal = 32.dp),
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.basicMarquee(iterations = MARQUEE_PASSES, initialDelayMillis = 1_500).padding(horizontal = 32.dp),
                )
            }
        }

        // A plain row rather than ButtonGroup: the seek ring is drawn around the screen centre, so the
        // play button must stay a fixed circle there instead of trading width with its neighbours.
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkipButton(R.drawable.skip_previous, R.string.previous, haptics) { player?.seekToPrevious() }
            PlayPauseButton(player, nowPlaying.playing, accent, haptics)
            SkipButton(R.drawable.skip_next, R.string.next, haptics) { player?.seekToNext() }
        }

        if (nowPlaying.mediaId != null) {
            IconToggleButton(
                checked = liked,
                onCheckedChange = onLikedChange,
                interactionSource = rememberPressHaptics(haptics),
                colors =
                    IconToggleButtonDefaults.colors(
                        checkedContainerColor = Color.Transparent,
                        checkedContentColor = accent,
                        uncheckedContainerColor = Color.Transparent,
                        uncheckedContentColor = Color.White,
                    ),
                shapes = IconToggleButtonDefaults.animatedShapes(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 34.dp, bottom = 30.dp),
            ) {
                Icon(
                    painterResource(if (liked) R.drawable.favorite else R.drawable.favorite_border),
                    stringResource(if (liked) R.string.unlike else R.string.like),
                )
            }
        }
    }
}

@Composable
private fun SkipButton(
    icon: Int,
    label: Int,
    haptics: Haptics,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
        interactionSource = rememberPressHaptics(haptics),
        shapes = IconButtonDefaults.animatedShapes(),
    ) { Icon(painterResource(icon), stringResource(label), Modifier.size(36.dp)) }
}

@Composable
fun PlayPauseButton(
    player: Player?,
    playing: Boolean,
    accent: Color,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    // Stays a circle (a pressed rounded square would poke out of the seek ring); a springy shrink is the press cue.
    val interactionSource = rememberPressHaptics(haptics)
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "playPress",
    )
    IconToggleButton(
        checked = playing,
        onCheckedChange = { player?.togglePlayback() },
        enabled = player?.currentMediaItem != null,
        colors =
            IconToggleButtonDefaults.colors(
                checkedContainerColor = accent,
                checkedContentColor = Color.Black,
                uncheckedContainerColor = accent,
                uncheckedContentColor = Color.Black,
            ),
        interactionSource = interactionSource,
        shapes = IconToggleButtonDefaults.shapes(CircleShape),
        modifier =
            modifier.size(PlayButtonSize).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Icon(
            painterResource(if (playing) R.drawable.pause else R.drawable.play),
            stringResource(if (playing) R.string.pause else R.string.play),
            Modifier.size(32.dp),
        )
    }
}

fun Player.togglePlayback() {
    if (playWhenReady) {
        pause()
    } else {
        if (playbackState == Player.STATE_ENDED) seekToDefaultPosition()
        play()
    }
}

/**
 * Drag that starts on the progress dot scrubs by angle (12 o'clock = start, clockwise) and wraps freely.
 * It runs in the Initial pass so a touch on the dot beats the play button and the pager.
 */
private fun Modifier.seekGesture(
    player: Player?,
    durationMs: Long,
    progress: Float,
    ringRadiusPx: Float,
    touchRadiusPx: Float,
    haptics: Haptics,
    onTargetChange: (Float?) -> Unit,
    onSeek: (Long) -> Unit,
): Modifier =
    composed {
        val currentProgress by rememberUpdatedState(progress)
        pointerInput(player, durationMs) {
            if (player == null || durationMs <= 0) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val center = Offset(size.width / 2f, size.height / 2f)
                val start = currentProgress
                if ((down.position - pointOnRing(center, ringRadiusPx, start)).getDistance() > touchRadiusPx) {
                    return@awaitEachGesture
                }
                down.consume()
                haptics.click()
                var previous = start
                onTargetChange(start)
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    val target = angleFraction(change.position - center)
                    if (crossesTop(previous, target) || crossesPoint(previous, target, start)) haptics.tick()
                    previous = target
                    onTargetChange(target)
                }
                // Landing exactly on 12 o'clock after wrapping would mean "end", which skips the song.
                val seekMs = (previous * durationMs).toLong().coerceAtMost(durationMs - 1_000).coerceAtLeast(0)
                player.seekTo(seekMs)
                onSeek(seekMs)
                onTargetChange(null)
            }
        }
    }

private fun angleFraction(fromCenter: Offset): Float {
    val angle = atan2(fromCenter.x, -fromCenter.y)
    return ((angle / (2 * PI)).toFloat() + 1f) % 1f
}

private fun pointOnRing(
    center: Offset,
    radius: Float,
    fraction: Float,
): Offset {
    val angle = 2 * PI * fraction - PI / 2
    return Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
}

// A jump of more than half a turn between two samples can only mean the finger went past 12 o'clock.
private fun crossesTop(
    from: Float,
    to: Float,
) = abs(to - from) > 0.5f

private fun crossesPoint(
    from: Float,
    to: Float,
    point: Float,
) = !crossesTop(from, to) && from != to && (from - point) * (to - point) <= 0f

@Composable
private fun SeekRing(
    progress: Float,
    target: Float?,
    accent: Color,
    radius: Dp,
    strokeWidth: Dp,
    dotRadius: Dp,
    trackAlpha: Float,
) {
    Canvas(Modifier.fillMaxSize()) {
        val radiusPx = radius.toPx()
        val stroke = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round)
        drawCircle(Color.White.copy(alpha = trackAlpha), radiusPx, center, style = Stroke(strokeWidth.toPx()))
        if (target == null) {
            drawRingArc(0f, progress, accent, radiusPx, stroke)
            drawCircle(Color.White, dotRadius.toPx(), pointOnRing(center, radiusPx, progress))
        } else {
            val low = min(progress, target)
            val high = max(progress, target)
            drawRingArc(0f, low, accent, radiusPx, stroke)
            // Forward: the stretch that would be skipped. Backward: the stretch that would be replayed.
            drawRingArc(low, high, if (target >= progress) Color.White.copy(alpha = 0.8f) else accent.copy(alpha = 0.45f), radiusPx, stroke)
            drawCircle(Color.White, dotRadius.toPx(), pointOnRing(center, radiusPx, progress))
            drawCircle(Color.White, dotRadius.toPx(), pointOnRing(center, radiusPx, target))
        }
    }
}

private fun DrawScope.drawRingArc(
    from: Float,
    to: Float,
    color: Color,
    radius: Float,
    stroke: Stroke,
) {
    if (to <= from) return
    drawArc(
        color = color,
        startAngle = -90f + 360f * from,
        sweepAngle = 360f * (to - from),
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
        style = stroke,
    )
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

internal fun lerp(
    start: Float,
    stop: Float,
    fraction: Float,
) = start + (stop - start) * fraction
