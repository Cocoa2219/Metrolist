package com.metrolist.wearprobe

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDateTime
import java.util.Locale

private const val TAG = "WearProbe"

private val QUERIES =
    listOf(
        "YOASOBI", "Kenshi Yonezu", "Ado", "Official HIGE DANdism", "Aimer", "LiSA",
        "NewJeans", "IU", "Taylor Swift", "The Weeknd", "Eminem", "Kendrick Lamar",
        "Billie Eilish", "Queen",
    )

/** Netscape cookies.txt (YouTube rows only) or a raw Cookie header → Cookie header value. */
internal fun parseCookie(text: String): String {
    val rows =
        text.lineSequence()
            .map { it.removePrefix("#HttpOnly_").split('\t') }
            .filter { it.size >= 7 && "youtube.com" in it[0] }
            .toList()
    if (rows.isNotEmpty()) return rows.joinToString("; ") { "${it[5]}=${it[6].trim()}" }
    if ('\t' in text) return ""
    return text.trim().removePrefix("Cookie:").trim()
}

class ProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()
    private val prefs by lazy { getSharedPreferences("probe", MODE_PRIVATE) }
    private val report = StringBuilder()
    private lateinit var output: TextView
    private var running = false
    private var sample: ExtractedStream? = null
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pad = (resources.displayMetrics.widthPixels * 0.12f).toInt()
        output = TextView(this).apply { textSize = 11f }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad * 2)
                addView(Button(context).apply { text = "Run test"; setOnClickListener { startRun() } })
                addView(Button(context).apply { text = "Play / stop sample"; setOnClickListener { togglePlayback() } })
                addView(output)
            }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        scroll.requestFocus()

        scope.launch { setup() }
    }

    override fun onDestroy() {
        player?.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        synchronized(report) { report.appendLine(line) }
        runOnUiThread { output.append(line + "\n") }
    }

    private suspend fun setup() {
        importCookieFile()
        requestWifi()
        // Like App.kt: InnerTube's default hl ("ko-KR") makes filtered song search return nothing.
        YouTube.locale = YouTubeLocale(gl = Locale.getDefault().country.ifEmpty { "US" }, hl = Locale.getDefault().language)
        YouTube.visitorData = YouTube.visitorData().getOrNull()
        log("visitorData: ${if (YouTube.visitorData != null) "ok" else "MISSING"}")
        val cookie = prefs.getString("cookie", null)
        if (cookie == null) {
            log("No cookie: signed-out test only")
        } else {
            YouTube.cookie = cookie
            YouTube.accountInfo()
                .onSuccess { log("Signed in as ${it.name}") }
                .onFailure { log("Cookie rejected: ${it.message}") }
        }
        log("Ready. Tap Run test.")
    }

    private fun importCookieFile() {
        val file = File(getExternalFilesDir(null), "cookies.txt")
        if (!file.exists()) return
        val cookie = parseCookie(file.readText())
        file.delete()
        if (cookie.isBlank()) {
            log("cookies.txt had no YouTube cookies")
        } else {
            prefs.edit().putString("cookie", cookie).apply()
            log("Imported cookie (file deleted)")
        }
    }

    // Wear OS routes traffic over the phone's Bluetooth proxy by default; media needs Wi-Fi or LTE.
    private suspend fun requestWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
        cm.requestNetwork(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                }
            },
        )
        for (attempt in 0 until 20) {
            if (cm.boundNetworkForProcess != null) break
            delay(500)
        }
        val caps = cm.getNetworkCapabilities(cm.boundNetworkForProcess ?: cm.activeNetwork)
        val transport =
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "LTE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth proxy"
                else -> "other"
            }
        log("Network: $transport")
    }

    private fun startRun() {
        if (running) return
        running = true
        runOnUiThread { output.text = "" }
        synchronized(report) { report.setLength(0) }
        scope.launch {
            try {
                runTest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Test crashed: $e")
            } finally {
                val file = File(getExternalFilesDir(null), "probe-report.txt")
                file.writeText(synchronized(report) { report.toString() })
                log("Report: ${file.absolutePath}")
                running = false
            }
        }
    }

    private suspend fun runTest() {
        log("Probe ${LocalDateTime.now()}")
        val tracks = collectTracks()
        log("Tracks: ${tracks.size}")
        val cookie = prefs.getString("cookie", null)
        val passes = listOf("signed-out" to null) + listOfNotNull(cookie?.let { "signed-in" to it })
        for ((name, passCookie) in passes) {
            YouTube.cookie = passCookie
            log("\n== Pass: $name ==")
            val extraction = newExtraction()
            var ok = 0
            val times = mutableListOf<Long>()
            val clients = mutableMapOf<String, Int>()
            tracks.forEachIndexed { index, song ->
                val result = probe(extraction.first, song)
                if (result.stream != null) {
                    ok++
                    times += result.ms
                    clients.merge(result.stream.clientName, 1, Int::plus)
                    if (sample == null) sample = result.stream
                }
                log("${index + 1}. ${result.line}")
            }
            extraction.second.dispose()
            times.sort()
            log("Result $name: $ok/${tracks.size} playable")
            if (times.isNotEmpty()) log("Resolve ms: median ${times[times.size / 2]}, max ${times.last()}")
            log("Clients: $clients")
        }
        YouTube.cookie = cookie
    }

    private suspend fun collectTracks(): List<SongItem> {
        val songs = LinkedHashMap<String, SongItem>()
        // Search never sends the login cookie unless it records history, so signed-in songs come from browse.
        if (YouTube.cookie != null) {
            YouTube.useLoginForBrowse = true
            YouTube.playlist("LM")
                .onFailure { log("Liked songs failed: $it") }
                .getOrNull()?.songs?.take(20)
                ?.forEach { songs.putIfAbsent(it.id, it) }
            YouTube.home()
                .onFailure { log("Home failed: $it") }
                .getOrNull()?.sections?.flatMap { it.items }
                ?.filterIsInstance<SongItem>()?.take(25)
                ?.forEach { songs.putIfAbsent(it.id, it) }
            log("Signed-in sources: ${songs.size} songs")
        }
        QUERIES.forEachIndexed { index, query ->
            // The FILTER_SONG param returns "No results" signed out (Oct 2026), so take songs from the unfiltered search.
            YouTube.searchSummary(query)
                .onFailure { log("Search '$query' failed: $it") }
                .getOrNull()
                ?.summaries?.flatMap { it.items }
                ?.filterIsInstance<SongItem>()
                ?.filter { it.musicVideoType == null || "ATV" in it.musicVideoType!! }
                ?.take(3)
                ?.forEach { songs.putIfAbsent(it.id, it) }
            // Every other query also contributes a music video, which goes through different clients.
            if (index % 2 == 0) {
                YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()
                    ?.items?.filterIsInstance<SongItem>()?.firstOrNull()
                    ?.let { songs.putIfAbsent(it.id, it) }
            }
        }
        return songs.values.take(50)
    }

    private class ProbeResult(val stream: ExtractedStream?, val ms: Long, val line: String)

    private suspend fun probe(extractor: InnerTubeExtractor, song: SongItem): ProbeResult {
        val label = "${song.title.take(28)} - ${song.artists.firstOrNull()?.name.orEmpty().take(16)}" +
            if (song.musicVideoType?.contains("OMV") == true || song.musicVideoType?.contains("UGC") == true) " [video]" else ""
        val start = SystemClock.elapsedRealtime()
        return try {
            val stream =
                withTimeout(45_000) {
                    extractor.extract(
                        song.id,
                        ContentHints(isExplicit = song.explicit)
                            .withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true),
                        emptySet(),
                        AudioQuality.AUTO,
                        generateClientPlaybackNonce(),
                    )
                } ?: return ProbeResult(null, 0, "FAIL no stream  $label")
            val ms = SystemClock.elapsedRealtime() - start
            val (code, bytes) = fetchHead(stream)
            if (code in 200..299 && bytes > 0) {
                ProbeResult(stream, ms, "OK ${ms}ms ${stream.clientName} itag${stream.itag}  $label")
            } else {
                ProbeResult(null, ms, "FAIL http $code via ${stream.clientName}  $label")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamResolveException) {
            val attempts = e.diagnostics?.attempts.orEmpty().joinToString(" | ") { it.toString().take(160) }
            ProbeResult(null, 0, "FAIL ${e.reason} ${e.message?.take(80)}  $label\n   $attempts")
        } catch (e: Exception) {
            ProbeResult(null, 0, "FAIL ${e::class.simpleName}: ${e.message?.take(80)}  $label")
        }
    }

    // Fetching real bytes catches URLs that resolve but are rejected (403) without a PoToken.
    private fun fetchHead(stream: ExtractedStream): Pair<Int, Int> {
        val request =
            Request.Builder()
                .url(stream.audioUrl)
                .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
                .header("Range", "bytes=0-65535")
                .build()
        return http.newCall(request).execute().use { it.code to (it.body?.bytes()?.size ?: 0) }
    }

    private fun togglePlayback() {
        player?.let {
            it.release()
            player = null
            log("Stopped")
            return
        }
        val stream = sample ?: return log("No playable stream yet; run the test first")
        val dataSource = OkHttpDataSource.Factory(http).setDefaultRequestProperties(stream.headers)
        player =
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(stream.audioUrl))
                    prepare()
                    play()
                }
        log("Playing ${stream.videoId} via ${stream.clientName}")
    }

    private fun newExtraction(): Pair<InnerTubeExtractor, YouTubeCipherService> {
        val transport = YouTube.extractionTransport()
        val remoteStore = RemotePlayerConfigStore(transport.httpClient, configRepository, logger)
        val cipherService = YouTubeCipherService(transport.httpClient, remoteStore, logger)
        val parser = YtConfigParserImpl(transport.httpClient, transport.innerTube, remoteStore, logger)
        val extractor =
            InnerTubeExtractor(
                configParser = parser.withEmbeddedConfigFallback(),
                cipherService = cipherService,
                innerTube = transport.innerTube,
                tokenProvider = NoTokenProvider,
                logger = logger,
            )
        return extractor to cipherService
    }

    private val configRepository by lazy { SharedPrefsPlayerConfigRepository(this) }

    private val logger =
        InnerTubeLogger { event ->
            val message = event.message + event.details.entries.joinToString(prefix = " [", postfix = "]")
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Log.d(event.tag, message)
                InnerTubeLogLevel.INFO -> Log.i(event.tag, message)
                InnerTubeLogLevel.WARN -> Log.w(event.tag, message)
                InnerTubeLogLevel.ERROR -> Log.e(event.tag, message)
            }
        }
}

/** The point of the probe: no WebView, so no BotGuard PoToken. */
private object NoTokenProvider : TokenProvider {
    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? = null
}

private fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
    object : YtConfigParser by this {
        override suspend fun fetchConfig(
            videoId: String,
            useLoginCookies: Boolean,
        ) = try {
            this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
        } catch (_: IllegalStateException) {
            this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
        }
    }

private class SharedPrefsPlayerConfigRepository(context: Context) : PlayerConfigRepository {
    private val preferences = context.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)

    override val enabled: Boolean = true
    override val sourceUrl: String = PLAYER_CONFIG_URL
    override val defaultSourceUrl: String = PLAYER_CONFIG_URL
    override var cachedJson: String
        get() = preferences.getString("json", "").orEmpty()
        set(value) = preferences.edit().putString("json", value).apply()
    override var cachedAtMs: Long
        get() = preferences.getLong("cached_at_ms", 0L)
        set(value) = preferences.edit().putLong("cached_at_ms", value).apply()
    override var cachedSourceUrl: String
        get() = preferences.getString("source_url", "").orEmpty()
        set(value) = preferences.edit().putString("source_url", value).apply()
    override var cachedEtag: String
        get() = preferences.getString("etag", "").orEmpty()
        set(value) = preferences.edit().putString("etag", value).apply()

    private companion object {
        const val PLAYER_CONFIG_URL =
            "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
    }
}
