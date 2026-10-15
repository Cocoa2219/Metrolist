package com.metrolist.wear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SongSection(val title: String, val songs: List<SongItem>)

class HomeViewModel : ViewModel() {
    private val _sections = MutableStateFlow<List<SongSection>?>(null)
    val sections: StateFlow<List<SongSection>?> = _sections

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _sections.value =
                YouTube.home().getOrNull()?.sections
                    ?.map { section -> SongSection(section.title, section.items.filterIsInstance<SongItem>()) }
                    ?.filter { it.songs.isNotEmpty() }
                    .orEmpty()
        }
    }
}

fun SongItem.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artists.joinToString { it.name })
                .setArtworkUri(thumbnail.largeThumbnail().toUri())
                .build(),
        ).build()

@Composable
fun HomePage(
    account: AccountState,
    nowPlayingId: String?,
    accent: Color,
    haptics: Haptics,
    listState: TransformingLazyColumnState,
    onPlay: (songs: List<SongItem>, index: Int) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val sections by viewModel.sections.collectAsState()
    val spec = rememberTransformationSpec()
    // The pager's indicator doubles as this list's scrollbar, so the scaffold draws none of its own.
    ScreenScaffold(scrollState = listState, scrollIndicator = {}) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.home_filled), stringResource(R.string.home), Modifier.size(26.dp))
                }
            }
            if (account !is AccountState.SignedIn) {
                item { AccountStatus(account) }
            }
            when (val loaded = sections) {
                null -> item { Text(stringResource(R.string.loading), textAlign = TextAlign.Center) }
                else ->
                    loaded.forEach { section ->
                        item { ListHeader { Text(section.title, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                        items(section.songs) { song ->
                            SongRow(
                                song = song,
                                playing = song.id == nowPlayingId,
                                accent = accent,
                                modifier = Modifier.transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                                haptics = haptics,
                            ) {
                                onPlay(section.songs, section.songs.indexOf(song))
                            }
                        }
                    }
            }
        }
    }
}

@Composable
fun SongRow(
    song: SongItem,
    playing: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
    haptics: Haptics,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        interactionSource = rememberPressHaptics(haptics),
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.childButtonColors(),
        transformation = transformation,
        icon = {
            AsyncImage(
                model = song.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
            )
        },
        secondaryLabel = {
            Text(
                song.artists.joinToString { it.name },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (playing) accent else Color.Unspecified)
    }
}

@Composable
fun AccountStatus(account: AccountState) {
    val (title, hint) =
        when (account) {
            AccountState.Loading -> stringResource(R.string.account_checking) to null
            AccountState.SignedOut -> stringResource(R.string.account_signed_out) to stringResource(R.string.account_signed_out_hint)
            is AccountState.SignedIn -> stringResource(R.string.account_signed_in, account.name) to null
            AccountState.Failed -> stringResource(R.string.account_failed) to stringResource(R.string.account_failed_hint)
        }
    Column(Modifier.padding(horizontal = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}
