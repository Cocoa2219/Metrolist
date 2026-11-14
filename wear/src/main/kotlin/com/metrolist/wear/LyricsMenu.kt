package com.metrolist.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.metrolist.music.lyrics.LyricsCandidate
import com.metrolist.music.lyrics.LyricsQuality

@Composable
fun LyricsMenu(
    nowPlaying: NowPlaying,
    store: LyricsStore,
    haptics: Haptics,
    onOtherSources: () -> Unit,
) {
    val lyrics by store.lyrics.collectAsState()
    val romanize by store.romanize.collectAsState()
    val offsetMs by store.offsetMs.collectAsState()
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    MenuBackground(nowPlaying) {
        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                item { ListHeader(Modifier.transformedHeight(this, spec)) { Text(stringResource(R.string.lyrics)) } }
                item {
                    Button(
                        onClick = {
                            haptics.click()
                            onOtherSources()
                        },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text(stringResource(R.string.lyrics_other_sources)) },
                        secondaryLabel = (lyrics as? WearLyrics.Found)?.let { { Text(it.provider) } },
                    )
                }
                item {
                    SwitchButton(
                        checked = romanize,
                        onCheckedChange = {
                            haptics.click()
                            store.setRomanize(it)
                        },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                        label = { Text(stringResource(R.string.lyrics_romanization)) },
                    )
                }
                item {
                    ListHeader(Modifier.transformedHeight(this, spec)) {
                        Text(
                            if (offsetMs == 0L) {
                                stringResource(R.string.lyrics_timing)
                            } else {
                                stringResource(R.string.lyrics_timing_offset, offsetMs / 1000f)
                            },
                        )
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().transformedHeight(this, spec),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                haptics.click()
                                store.shiftOffset(nowPlaying, -LYRICS_OFFSET_STEP_MS)
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.lyrics_earlier)) },
                        )
                        FilledTonalButton(
                            onClick = {
                                haptics.click()
                                store.shiftOffset(nowPlaying, LYRICS_OFFSET_STEP_MS)
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.lyrics_later)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LyricsSources(
    nowPlaying: NowPlaying,
    store: LyricsStore,
    haptics: Haptics,
    onChosen: () -> Unit,
) {
    val lyrics by store.lyrics.collectAsState()
    val current = (lyrics as? WearLyrics.Found)?.provider
    var sources by remember { mutableStateOf<List<LyricsCandidate>?>(null) }
    LaunchedEffect(nowPlaying.mediaId) { sources = store.allSources(nowPlaying) }
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    MenuBackground(nowPlaying) {
        when (val found = sources) {
            null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            emptyList<LyricsCandidate>() ->
                Text(
                    stringResource(R.string.lyrics_no_sources),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            else ->
                ScreenScaffold(scrollState = listState) { contentPadding ->
                    TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                        item { ListHeader(Modifier.transformedHeight(this, spec)) { Text(stringResource(R.string.lyrics_other_sources)) } }
                        items(found, key = { it.provider }) { source ->
                            Button(
                                onClick = {
                                    haptics.click()
                                    if (source.provider != current) store.choose(nowPlaying, source)
                                    onChosen()
                                },
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                                colors =
                                    if (source.provider == current) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                                label = { Text(source.provider) },
                                secondaryLabel = { Text(stringResource(source.quality.label)) },
                            )
                        }
                    }
                }
        }
    }
}

private val LyricsQuality.label
    get() =
        when (this) {
            LyricsQuality.WORD_SYNCED -> R.string.lyrics_word_synced
            LyricsQuality.LINE_SYNCED -> R.string.lyrics_line_synced
            else -> R.string.lyrics_not_synced
        }

// The song's artwork stays behind its menus, as on the lyrics screen, just darker.
@Composable
private fun MenuBackground(
    nowPlaying: NowPlaying,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        ArtworkBackground(nowPlaying.artworkUri, dim = { 0.85f }, vignette = { 1f }, textScrim = { 0f }, blackout = { 0f })
        content()
    }
}
