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

    private suspend fun fetchTTML(
        artist: String,
        title: String,
        album: String? = null,
        duration: Int = -1,
    ): String? {
        // Use exact title and artist - no normalization to ensure correct sync
        // Normalizing can return wrong lyrics (e.g., radio edit vs original)
        val searchTitle = title.trim()
        val searchArtist = artist.trim()
        
        logger?.invoke("Searching: title='$searchTitle', artist='$searchArtist', album='$album'")
        
        return try {
            val response: HttpResponse = client.get("/ttml/getLyrics") {
                parameter("s", searchTitle)
                if (searchArtist.isNotBlank()) {
                    parameter("a", searchArtist)
                }
                album?.let { parameter("al", it) }
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
