// Modified by Metrolist: scenes carry their source lyrics so a previous scene can stay on screen.
package com.mocharealm.accompanist.lyrics.ui.internal.scene

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.ui.internal.playback.LyricsPlaybackTimeline
import com.mocharealm.accompanist.lyrics.ui.preparation.PreparedLyrics
import com.mocharealm.accompanist.lyrics.ui.internal.rendering.LyricsRenderResources

/** A published scene has complete geometry and rasters, with a view-local playback owner. */
internal class LyricsScene(
    val source: SyncedLyrics,
    val lyrics: PreparedLyrics,
    val timeline: LyricsPlaybackTimeline,
    val resources: LyricsRenderResources,
    val items: LyricsItemMapping,
)
