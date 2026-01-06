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

            expectSuccess = false
        }
    }

    private suspend fun fetchTTML(
        artist: String,
        title: String,
        album: String? = null,
        duration: Int = -1,
    ): String? {
        return try {
            val response: HttpResponse = client.get("/ttml/getLyrics") {
                parameter("s", title)
                parameter("a", artist)
                album?.let { parameter("al", it) }
                if (duration != -1) {
                    parameter("d", duration)
                }
            }
            
            if (!response.status.isSuccess()) {
                return null
            }
            
            val ttmlResponse = response.body<TTMLResponse>()
            ttmlResponse.ttml.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
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
        
        val parsedLines = TTMLParser.parseTTML(ttml)
        if (parsedLines.isEmpty()) {
            throw IllegalStateException("Failed to parse lyrics")
        }
        
        TTMLParser.toLRC(parsedLines)
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        getLyrics(title, artist, album, duration)
            .onSuccess { lrcString ->
                callback(lrcString)
            }
    }
}
