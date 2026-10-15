package com.metrolist.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.wear.ambient.AmbientLifecycleObserver.AmbientDetails
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlin.random.Random

private const val BURN_IN_SHIFT_PX = 6

/** Always-on player: black, monochrome, nothing that redraws more than once a minute. */
@Composable
fun AmbientPlayer(
    nowPlaying: NowPlaying,
    liked: Boolean,
    details: AmbientDetails,
    minuteTick: Int,
) {
    // Low-bit screens can't show grey, so everything that would be grey goes white there.
    val secondary = if (details.deviceHasLowBitAmbient) Color.White else Color(0xFF9E9E9E)
    val shift =
        remember(minuteTick, details.burnInProtectionRequired) {
            if (details.burnInProtectionRequired) {
                IntOffset(Random.nextInt(-BURN_IN_SHIFT_PX, BURN_IN_SHIFT_PX + 1), Random.nextInt(-BURN_IN_SHIFT_PX, BURN_IN_SHIFT_PX + 1))
            } else {
                IntOffset.Zero
            }
        }

    Box(Modifier.fillMaxSize().background(Color.Black).offset { shift }) {
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 34.dp, start = 36.dp, end = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                nowPlaying.title.ifEmpty { stringResource(R.string.nothing_playing) },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                nowPlaying.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.skip_previous), null, Modifier.size(36.dp), tint = secondary)
            Box(Modifier.size(PlayButtonSize).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(if (nowPlaying.playing) R.drawable.pause else R.drawable.play),
                    null,
                    Modifier.size(32.dp),
                    tint = Color.White,
                )
            }
            Icon(painterResource(R.drawable.skip_next), null, Modifier.size(36.dp), tint = secondary)
        }
        if (nowPlaying.mediaId != null) {
            Icon(
                painterResource(if (liked) R.drawable.favorite else R.drawable.favorite_border),
                null,
                // Same centre as the interactive like button (52dp toggle, padded 34dp / 30dp).
                Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 44.dp).size(24.dp),
                tint = secondary,
            )
        }
    }
}
