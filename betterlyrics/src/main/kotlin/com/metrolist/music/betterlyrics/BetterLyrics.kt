package com.metrolist.music.betterlyrics

import com.metrolist.music.betterlyrics.models.TTMLResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object BetterLyrics {
    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    },
                )
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }

            defaultRequest {
                url("https://lyrics-api.boidu.dev")
            }

            expectSuccess = true
        }
    }

    // Clean and normalize text for better search results
    private fun normalizeText(text: String): String {
        return text
            // Remove content in parentheses (feat., ft., etc.)
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            // Remove content in square brackets ([Explicit], [Remaster], etc.)
            .replace(Regex("\\s*\\[[^]]*\\]"), "")
            // Remove "feat.", "ft.", "featuring" and everything after
            .replace(Regex("\\s*(?:feat\\.?|ft\\.?|featuring)\\s+.*", RegexOption.IGNORE_CASE), "")
            // Remove special characters except basic punctuation
            .replace(Regex("[''`]"), "'")
            .replace(Regex("[""„]"), "\"")
            // Remove extra whitespace
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // Extract primary artist (first artist before comma, &, etc.)
    private fun extractPrimaryArtist(artist: String): String {
        return artist
            .split(Regex("[,&]|\\s+(?:and|x|vs\\.?)\\s+", RegexOption.IGNORE_CASE))
            .firstOrNull()
            ?.let { normalizeText(it) }
            ?: normalizeText(artist)
    }

    private suspend fun fetchTTML(
        artist: String,
        title: String,
        duration: Int = -1,
    ): String? {
        val normalizedTitle = normalizeText(title)
        val normalizedArtist = normalizeText(artist)
        val primaryArtist = extractPrimaryArtist(artist)

        // Try different search strategies
        val searchStrategies = listOf(
            // Strategy 1: Normalized title and artist
            Pair(normalizedTitle, normalizedArtist),
            // Strategy 2: Normalized title with primary artist only
            Pair(normalizedTitle, primaryArtist),
            // Strategy 3: Original title with normalized artist
            Pair(title.trim(), normalizedArtist),
            // Strategy 4: Title only (for cases where artist name differs)
            Pair(normalizedTitle, ""),
        )

        for ((searchTitle, searchArtist) in searchStrategies) {
            if (searchTitle.isBlank()) continue
            
            val result = runCatching {
                val response = client.get("/getLyrics") {
                    parameter("s", searchTitle)
                    if (searchArtist.isNotBlank()) {
                        parameter("a", searchArtist)
                    }
                    if (duration > 0) {
                        parameter("d", duration)
                    }
                }.body<TTMLResponse>()
                response.ttml
            }.getOrNull()

            if (!result.isNullOrBlank()) {
                return result
            }
        }

        return null
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
    ) = runCatching {
        val ttml = fetchTTML(artist, title, duration)
            ?: throw IllegalStateException("Lyrics unavailable")
        
        // Parse TTML and convert to LRC format
        val parsedLines = TTMLParser.parseTTML(ttml)
        if (parsedLines.isEmpty()) {
            throw IllegalStateException("Failed to parse lyrics")
        }
        
        TTMLParser.toLRC(parsedLines)
    }


    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        // The new API returns a single TTML result, not multiple options
        getLyrics(title, artist, duration)
            .onSuccess { lrcString ->
                callback(lrcString)
            }
    }
}
