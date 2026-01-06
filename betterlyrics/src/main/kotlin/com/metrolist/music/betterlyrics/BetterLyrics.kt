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
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object BetterLyrics {
    private const val API_BASE_URL = "https://lyrics-api.boidu.dev/"
    
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
                requestTimeoutMillis = 20000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 20000
            }

            defaultRequest {
                url(API_BASE_URL)
            }
            
            // Don't throw on non-2xx responses, handle them gracefully
            expectSuccess = false
        }
    }

    var logger: ((String) -> Unit)? = null

    // Clean and normalize text for better search results
    private fun normalizeText(text: String): String {
        return text
            // Remove content in parentheses (feat., ft., etc.)
            .replace("\\s*\\([^)]*\\)".toRegex(), "")
            // Remove content in square brackets ([Explicit], [Remaster], etc.)
            .replace("\\s*\\[[^]]*\\]".toRegex(), "")
            // Remove "feat.", "ft.", "featuring" and everything after
            .replace("(?i)\\s*(?:feat\\.?|ft\\.?|featuring)\\s+.*".toRegex(), "")
            // Remove fancy quotes with regular ones
            .replace('\u2018', '\'') // '
            .replace('\u2019', '\'') // '
            .replace('\u0060', '\'') // `
            .replace('\u201C', '"')  // "
            .replace('\u201D', '"')  // "
            .replace('\u201E', '"')  // „
            // Remove extra whitespace
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    // Extract primary artist (first artist before comma, &, etc.)
    private fun extractPrimaryArtist(artist: String): String {
        return artist
            .split("(?i)[,&]|\\s+(?:and|x|vs\\.?)\\s+".toRegex())
            .firstOrNull()
            ?.let { normalizeText(it) }
            ?: normalizeText(artist)
    }

    private suspend fun fetchTTML(
        artist: String,
        title: String,
        album: String? = null,
        duration: Int = -1,
    ): String? {
        val normalizedTitle = normalizeText(title)
        val normalizedArtist = normalizeText(artist)
        val primaryArtist = extractPrimaryArtist(artist)

        // Try different search strategies
        val searchStrategies = listOf(
            // Strategy 1: Normalized title and artist with album
            Triple(normalizedTitle, normalizedArtist, album),
            // Strategy 2: Normalized title with primary artist only
            Triple(normalizedTitle, primaryArtist, album),
            // Strategy 3: Original title with normalized artist
            Triple(title.trim(), normalizedArtist, album),
            // Strategy 4: Title only (for cases where artist name differs)
            Triple(normalizedTitle, "", null),
        )

        for ((searchTitle, searchArtist, searchAlbum) in searchStrategies) {
            if (searchTitle.isBlank()) continue
            
            logger?.invoke("Trying search: title='$searchTitle', artist='$searchArtist', album='$searchAlbum'")
            
            val result = try {
                val response: HttpResponse = client.get("/ttml/getLyrics") {
                    parameter("s", searchTitle)
                    if (searchArtist.isNotBlank()) {
                        parameter("a", searchArtist)
                    }
                    searchAlbum?.let { parameter("al", it) }
                    if (duration > 0) {
                        parameter("d", duration)
                    }
                }
                
                logger?.invoke("Response Status: ${response.status}")
                
                if (!response.status.isSuccess()) {
                    logger?.invoke("Request failed with status: ${response.status}")
                    null
                } else {
                    val ttmlResponse = response.body<TTMLResponse>()
                    val ttml = ttmlResponse.ttml
                    
                    if (ttml.isNotBlank()) {
                        logger?.invoke("Received TTML (length: ${ttml.length})")
                        ttml
                    } else {
                        logger?.invoke("Received empty TTML")
                        null
                    }
                }
            } catch (e: Exception) {
                logger?.invoke("Error fetching lyrics: ${e.message}")
                null
            }

            if (!result.isNullOrBlank()) {
                return result
            }
        }

        return null
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ) = runCatching {
        val ttml = fetchTTML(artist, title, album, duration)
            ?: throw IllegalStateException("Lyrics unavailable")
        ttml
    }

    // Backward compatibility overload
    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
    ) = getLyrics(title, artist, null, duration)

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        val result = getLyrics(title, artist, album, duration)
        result.onSuccess { ttml ->
            callback(ttml)
        }
    }

    // Backward compatibility overload
    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        getAllLyrics(title, artist, null, duration, callback)
    }
}
