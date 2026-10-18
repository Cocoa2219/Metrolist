package com.metrolist.wear

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay

private enum class SkipFlash { Previous, Next }

@Composable
fun ArtworkPage(
    player: Player?,
    nowPlaying: NowPlaying,
    volume: VolumeState,
    haptics: Haptics,
) {
    var flash by remember { mutableStateOf<SkipFlash?>(null) }
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(600)
            flash = null
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .rotaryVolume(volume, haptics)
            .pointerInput(player) {
                if (player == null) return@pointerInput
                // onTap waits out the double-tap window, so the click goes on touch-down instead.
                detectTapGestures(
                    onPress = { haptics.click() },
                    onTap = { player.togglePlayback() },
                    onDoubleTap = { offset ->
                        if (offset.x > size.width / 2f) {
                            player.seekToNext()
                            flash = SkipFlash.Next
                        } else {
                            player.seekToPrevious()
                            flash = SkipFlash.Previous
                        }
                    },
                )
            },
    ) {
        // Full width like the player, so marquee ends fall past the round edge.
        SongChangeTransition(nowPlaying, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 30.dp)) { song ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    song.title.ifEmpty { stringResource(R.string.nothing_playing) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = MARQUEE_PASSES, initialDelayMillis = 1_500).padding(horizontal = 24.dp),
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = MARQUEE_PASSES, initialDelayMillis = 1_500).padding(horizontal = 24.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = !nowPlaying.playing && nowPlaying.mediaId != null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(Modifier.size(68.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.play), stringResource(R.string.play), Modifier.size(36.dp), tint = Color.Black)
            }
        }
        SkipFlashIcon(flash == SkipFlash.Previous, R.drawable.skip_previous, Modifier.align(Alignment.CenterStart).padding(start = 28.dp))
        SkipFlashIcon(flash == SkipFlash.Next, R.drawable.skip_next, Modifier.align(Alignment.CenterEnd).padding(end = 28.dp))
    }
}

@Composable
private fun SkipFlashIcon(
    visible: Boolean,
    icon: Int,
    modifier: Modifier,
) {
    AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 0.6f), exit = fadeOut(), modifier = modifier) {
        Icon(painterResource(icon), null, Modifier.size(40.dp), tint = Color.White)
    }
}
