/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.kugou.KuGou
import com.metrolist.music.constants.EnableKugouKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get

object KuGouLyricsProvider : LyricsProvider {
    override val name = "Kugou"
    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableKugouKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        val match = context.durationMatch(name)
        return KuGou.getLyrics(title, artist, match.durationFor(duration), album, match.toleranceSeconds)
    }

    override suspend fun getAllLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        val match = context.durationMatch(name)
        KuGou.getAllPossibleLyricsOptions(title, artist, match.durationFor(duration), album, callback, match.toleranceSeconds)
    }
}
