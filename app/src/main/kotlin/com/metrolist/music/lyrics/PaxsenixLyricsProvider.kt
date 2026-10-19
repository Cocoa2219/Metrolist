/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.paxsenix.Paxsenix
import com.metrolist.paxsenix.ResultFilter
import com.metrolist.music.constants.EnablePaxsenixKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import timber.log.Timber

object PaxsenixLyricsProvider : LyricsProvider {
    private const val TAG = "PaxsenixProvider"
    
    override val name = "Paxsenix"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnablePaxsenixKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        Timber.tag(TAG).d("getLyrics called: title='$title', artist='$artist', duration=$duration")
        
        try {
            Paxsenix.init(context)
            // Apple's catalog often lists K-pop and J-pop under English names.
            val result = Paxsenix.getLyrics(title, artist, duration, album, context.resultFilter(title))
                .recoverCatching { error ->
                    if (!context.lyricsSearchOptions().englishNames) throw error
                    val english = EnglishSongNames.get(id, title, artist) ?: throw error
                    Paxsenix.getLyrics(
                        english.title, english.artist, duration, english.album ?: album, context.resultFilter(english.title),
                    ).getOrThrow()
                }

            result.onSuccess { lyrics ->
                Timber.tag(TAG).i("Success! Got ${lyrics.length} chars of lyrics")
            }.onFailure { e ->
                Timber.tag(TAG).e(e, "Failed to get lyrics")
            }
            
            return result
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Exception in getLyrics")
            return Result.failure(e)
        }
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
        Timber.tag(TAG).d("getAllLyrics called")
        try {
            Paxsenix.init(context)
            Paxsenix.getAllLyrics(title, artist, duration, album, callback, context.resultFilter(title))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error fetching lyrics from Paxsenix")
            callback("")
        }
    }

    private fun Context.resultFilter(title: String): ResultFilter {
        val rules = matchRules(name)
        return ResultFilter(
            requireDuration = rules.duration.required,
            toleranceSeconds = rules.duration.toleranceSeconds,
            exactTitle = rules.exactTitle,
            matchArtist = rules.matchArtist,
            rejectsTitle = { rules.rejectsVersion(title, it) },
        )
    }
}
