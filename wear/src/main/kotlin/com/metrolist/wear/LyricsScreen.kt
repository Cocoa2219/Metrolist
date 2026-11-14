package com.metrolist.wear

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.coroutines.delay

// Lines turn current slightly early, so the eye arrives as the singer does.
private const val LINE_LEAD_MS = 300L
private const val POSITION_POLL_MS = 200L
// After the user scrolls, follow-along pauses this long before gliding back to the current line.
private const val USER_SCROLL_HOLD_MS = 3_000L
private const val LINE_GLIDE_MS = 450

@Composable
fun LyricsScreen(
    player: Player?,
    nowPlaying: NowPlaying,
    accent: Color,
    store: LyricsStore,
    haptics: Haptics,
    onOpenMenu: () -> Unit,
) {
    LaunchedEffect(nowPlaying.mediaId, nowPlaying.durationMs) { store.load(nowPlaying) }
    val lyrics by store.lyrics.collectAsState()
    val romanize by store.romanize.collectAsState()
    val offsetMs by store.offsetMs.collectAsState()

    // Polled faster than the player page's ring: a line change a second late is noticeable.
    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player, nowPlaying.playing) {
        while (player != null) {
            positionMs = player.currentPosition
            if (!nowPlaying.playing) break
            delay(POSITION_POLL_MS)
        }
    }

    val found = lyrics as? WearLyrics.Found
    val currentIndex =
        if (found?.synced == true) {
            found.lines.indexOfLast { line -> line.timeMs?.let { it + offsetMs <= positionMs + LINE_LEAD_MS } == true }
        } else {
            -1
        }

    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    var autoScrolling by remember { mutableStateOf(false) }
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) userScrolledAt = System.currentTimeMillis()
        }
    }
    // Item 0 is the song header, so line i is item i + 1.
    LaunchedEffect(currentIndex, userScrolledAt, found) {
        if (currentIndex < 0) return@LaunchedEffect
        val sinceUser = System.currentTimeMillis() - userScrolledAt
        if (sinceUser < USER_SCROLL_HOLD_MS) delay(USER_SCROLL_HOLD_MS - sinceUser)
        autoScrolling = true
        try {
            listState.glideToCentre(currentIndex + 1)
        } finally {
            autoScrolling = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        ArtworkBackground(nowPlaying.artworkUri, dim = { 0.72f }, vignette = { 1f }, textScrim = { 0f }, blackout = { 0f })
        when (val state = lyrics) {
            WearLyrics.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            WearLyrics.NotFound ->
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.lyrics_not_found),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    MenuButton(haptics, onOpenMenu)
                }
            is WearLyrics.Found ->
                ScreenScaffold(scrollState = listState) { contentPadding ->
                    TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(nowPlaying.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(nowPlaying.artist, style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                MenuButton(haptics, onOpenMenu)
                            }
                        }
                        itemsIndexed(state.lines) { index, line ->
                            LyricRow(
                                line = line,
                                showRomanized = romanize,
                                current = index == currentIndex,
                                passed = state.synced && index < currentIndex,
                                modifier = Modifier.transformedHeight(this, spec),
                                onClick =
                                    line.timeMs?.let { time ->
                                        {
                                            haptics.click()
                                            player?.seekTo((time + offsetMs).coerceAtLeast(0L))
                                        }
                                    },
                            )
                        }
                    }
                }
        }
    }
}

/**
 * Scrolls [index] to the middle with one eased glide. A line further than the screen away, as after a seek, is
 * jumped to instead: gliding across half a song would be a blur.
 */
private suspend fun TransformingLazyColumnState.glideToCentre(index: Int) {
    val info = layoutInfo
    val item = info.visibleItems.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index)
        return
    }
    val distance = item.offset + item.transformedHeight / 2 - info.viewportSize.height / 2
    if (distance != 0) animateScrollBy(distance.toFloat(), tween(LINE_GLIDE_MS, easing = FastOutSlowInEasing))
}

@Composable
private fun MenuButton(
    haptics: Haptics,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = {
            haptics.click()
            onClick()
        },
        modifier = Modifier.size(36.dp),
    ) {
        Icon(painterResource(R.drawable.more_horiz), stringResource(R.string.lyrics_options), tint = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
private fun LyricRow(
    line: LyricLine,
    showRomanized: Boolean,
    current: Boolean,
    passed: Boolean,
    modifier: Modifier,
    onClick: (() -> Unit)?,
) {
    val color by animateColorAsState(
        when {
            current -> Color.White
            passed -> Color.White.copy(alpha = 0.35f)
            else -> Color.White.copy(alpha = 0.5f)
        },
        label = "lyricLine",
    )
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(interactionSource = null, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            line.text,
            style = MaterialTheme.typography.titleMedium,
            // One weight for every line: bolding the current one rewraps it and shifts the list mid-glide.
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
        )
        if (showRomanized) {
            line.romanized?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = color.alpha * 0.75f), textAlign = TextAlign.Center)
            }
        }
    }
}
