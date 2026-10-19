/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.lrclib.LrcLib
import com.metrolist.music.constants.EnableLrcLibKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get

object LrcLibLyricsProvider : LyricsProvider {
    override val name = "LrcLib"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableLrcLibKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        val match = context.durationMatch(name)
        return LrcLib.getLyrics(title, artist, match.durationFor(duration), album, match.toleranceSeconds)
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
        LrcLib.getAllLyrics(title, artist, match.durationFor(duration), album, callback, match.toleranceSeconds)
    }
}
