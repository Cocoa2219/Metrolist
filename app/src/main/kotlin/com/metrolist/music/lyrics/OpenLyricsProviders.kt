/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.betterlyrics.TTMLParser
import com.metrolist.music.constants.EnableAmllKey
import com.metrolist.music.constants.EnableLrcMuxKey
import com.metrolist.music.constants.EnableLrcRedKey
import com.metrolist.music.constants.EnableUnisonKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import kotlin.math.abs

// Sources whose public APIs are open to any client. Each answers in Metrolist's LRC dialect: TTML goes
// through TTMLParser, LRCMux's word JSON becomes rich-sync LRC.

private val client by lazy {
    HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 8_000
        }
        expectSuccess = false
    }
}

/** The response body as JSON, or null on a non-2xx answer (404 is how these APIs say "no lyrics"). */
private suspend fun getJson(url: String, block: HttpRequestBuilder.() -> Unit = {}): JsonObject? {
    val response = client.get(url) {
        header(HttpHeaders.Accept, "application/json")
        block()
    }
    if (!response.status.isSuccess()) return null
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
}

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { it.string() }.orEmpty()

private fun matchKey(text: String) =
    LyricsUtils.cleanTitleForSearch(text).lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

private fun sameTitle(title: String, candidate: String) = matchKey(title) == matchKey(candidate)

/** [artists] is Metrolist's "A, B" list; one shared artist is enough. */
private fun sharesArtist(artists: String, candidate: String): Boolean {
    val wanted = artists.split(",", "&").map(::matchKey).filter(String::isNotEmpty)
    val key = matchKey(candidate)
    return wanted.any { it in key || key in it }
}

private fun ttmlToLrc(ttml: String): String =
    TTMLParser.parseTTML(ttml).takeIf { it.isNotEmpty() }?.let(TTMLParser::toLRC)
        ?: error("Failed to parse lyrics")

/** AMLL TTML DB: community word-synced TTML (CC0), searched by title and artist. */
object AmllLyricsProvider : LyricsProvider {
    override val name = "AMLL"
    private const val BASE = "https://api.amll.dev/v1/lyrics"

    override fun isEnabled(context: Context) = context.dataStore[EnableAmllKey] ?: true

    override suspend fun getLyrics(
        context: Context, id: String, title: String, artist: String, duration: Int, album: String?,
    ): Result<String> = runCatching {
        val items = getJson("$BASE/search") {
            parameter("trackName", title)
            parameter("artistName", artist)
        }?.get("data")?.jsonObject?.get("items")?.jsonArray.orEmpty()
        // The search is fuzzy (an artist's other songs come back too), so only an exact title counts.
        val match = items.map { it.jsonObject }.firstOrNull { item ->
            item["musicNames"].strings().any { sameTitle(title, it) } &&
                item["artistNames"].strings().any { sharesArtist(artist, it) }
        } ?: error("Lyrics unavailable")
        val ttml = getJson("$BASE/get") { parameter("id", match["id"].string()) }
            ?.get("data")?.jsonObject?.get("lyrics").string()
            ?: error("Lyrics unavailable")
        ttmlToLrc(ttml)
    }
}

/** Unison: community syncs keyed by YouTube video ID, so Metrolist can ask for the exact video first. */
object UnisonLyricsProvider : LyricsProvider {
    override val name = "Unison"
    private const val BASE = "https://unison.boidu.dev/lyrics"
    private const val DURATION_TOLERANCE_S = 5

    override fun isEnabled(context: Context) = context.dataStore[EnableUnisonKey] ?: true

    override suspend fun getLyrics(
        context: Context, id: String, title: String, artist: String, duration: Int, album: String?,
    ): Result<String> = runCatching {
        val data = getJson(BASE) { parameter("v", id) }?.get("data")?.jsonObject
            ?: getJson(BASE) {
                parameter("song", title)
                parameter("artist", artist)
            }?.get("data")?.jsonObject?.takeIf { found ->
                val foundDuration = found["duration"]?.jsonPrimitive?.longOrNull
                duration <= 0 || foundDuration == null || abs(foundDuration - duration) <= DURATION_TOLERANCE_S
            }
            ?: error("Lyrics unavailable")
        val lyrics = data["lyrics"].string()?.takeIf(String::isNotBlank) ?: error("Lyrics unavailable")
        if (data["format"].string() == "ttml" || lyrics.contains("<tt", ignoreCase = true)) ttmlToLrc(lyrics) else lyrics
    }
}

/** LRCMux: aggregates other sources and returns word timing as JSON lines in milliseconds. */
object LrcMuxLyricsProvider : LyricsProvider {
    override val name = "LRCMux"

    override fun isEnabled(context: Context) = context.dataStore[EnableLrcMuxKey] ?: true

    override suspend fun getLyrics(
        context: Context, id: String, title: String, artist: String, duration: Int, album: String?,
    ): Result<String> = runCatching {
        val lines = getJson("https://api.lrcmux.dev/get") {
            parameter("artist", artist)
            parameter("title", title)
            if (duration > 0) parameter("duration", duration)
            parameter("format", "json")
            parameter("level", "word")
        }?.get("lines")?.jsonArray?.takeIf { it.isNotEmpty() } ?: error("Lyrics unavailable")

        lines.joinToString("\n") { element ->
            val line = element.jsonObject
            val start = line["start"]?.jsonPrimitive?.longOrNull ?: 0L
            val words = line["words"]?.jsonArray.orEmpty().map { it.jsonObject }
            if (words.isEmpty()) {
                "[${lrcTime(start)}]${line["text"].string().orEmpty()}"
            } else {
                // Word texts carry their own spacing; the trailing tag is the last word's end.
                val end = words.last()["end"]?.jsonPrimitive?.longOrNull ?: start
                "[${lrcTime(start)}]" + words.joinToString("") { word ->
                    "<${lrcTime(word["start"]?.jsonPrimitive?.longOrNull ?: start)}>${word["text"].string().orEmpty()}"
                } + "<${lrcTime(end)}>"
            }
        }
    }

    private fun lrcTime(ms: Long) = String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000)
}

/** lrc.red: Apple Music's TTML by ISRC, found through its site search. */
object LrcRedLyricsProvider : LyricsProvider {
    override val name = "LrcRed"
    private const val BASE = "https://lrc.red"
    private const val DURATION_TOLERANCE_S = 3.0

    override fun isEnabled(context: Context) = context.dataStore[EnableLrcRedKey] ?: false

    override suspend fun getLyrics(
        context: Context, id: String, title: String, artist: String, duration: Int, album: String?,
    ): Result<String> = runCatching {
        val hits = getJson("$BASE/search.json") { parameter("q", "$title $artist") }?.get("hits")?.jsonArray.orEmpty()
        val isrc = hits.map { it.jsonObject }.firstOrNull { hit ->
            val hitDuration = hit["duration"]?.jsonPrimitive?.doubleOrNull
            sameTitle(title, hit["title"].string().orEmpty()) &&
                sharesArtist(artist, hit["artist"].string().orEmpty()) &&
                (duration <= 0 || hitDuration == null || abs(hitDuration - duration) <= DURATION_TOLERANCE_S)
        }?.get("isrc").string() ?: error("Lyrics unavailable")
        val response = client.get("$BASE/s/$isrc.ttml")
        if (!response.status.isSuccess()) error("Lyrics unavailable")
        ttmlToLrc(response.bodyAsText())
    }
}
