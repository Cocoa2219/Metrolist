package com.metrolist.wear

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.metrolist.extraction.InnerTubeXPlayer
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertube.utils.completed
import com.metrolist.music.lyrics.LyricsPhonetics
import com.mocharealm.accompanist.lyrics.phonetics.data.JapaneseData
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.util.Locale

val Context.dataStore by preferencesDataStore(name = "settings")

private val CookieKey = stringPreferencesKey("innerTubeCookie")
private val VisitorDataKey = stringPreferencesKey("visitorData")

sealed interface AccountState {
    data object Loading : AccountState

    data object SignedOut : AccountState

    data class SignedIn(val name: String) : AccountState

    data object Failed : AccountState
}

class WearApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountLock = Mutex()
    private val _account = MutableStateFlow<AccountState>(AccountState.Loading)
    val account: StateFlow<AccountState> = _account

    val lyrics by lazy { LyricsStore(this, scope) }

    // innertube has no per-song like status, so membership in the Liked songs playlist stands in for it.
    private val _likedSongs = MutableStateFlow<Set<String>>(emptySet())
    val likedSongs: StateFlow<Set<String>> = _likedSongs
    private var likedSongsLoaded = false

    fun setLiked(
        videoId: String,
        liked: Boolean,
    ) {
        _likedSongs.update { if (liked) it + videoId else it - videoId }
        scope.launch {
            YouTube.likeVideo(videoId, liked).onFailure {
                Timber.w(it, "Like change failed for $videoId")
                _likedSongs.update { songs -> if (liked) songs - videoId else songs + videoId }
            }
        }
    }

    private suspend fun loadLikedSongs() {
        if (likedSongsLoaded) return
        YouTube.playlist("LM").completed()
            .onSuccess { page ->
                _likedSongs.value = page.songs.mapTo(HashSet()) { it.id }
                likedSongsLoaded = true
            }.onFailure { Timber.w(it, "Could not load Liked songs") }
    }

    // SimpleCache allows one instance per folder for the process lifetime, so it can't live in the service.
    val playerCache by lazy {
        SimpleCache(
            File(cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(PLAYER_CACHE_BYTES),
            StandaloneDatabaseProvider(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        // A watch has no WebView, so no BotGuard PoTokens; Phase 0 showed playback works without them.
        InnerTubeXPlayer.initialize(this, NoTokenProvider)
        LyricsPhonetics.initialize(this) { packs -> listOf(JapaneseData.japanese(packs)) }
        val locale = Locale.getDefault()
        YouTube.locale = YouTubeLocale(gl = locale.country.ifEmpty { "US" }, hl = locale.language)
        YouTube.useLoginForBrowse = true

        scope.launch {
            val settings = dataStore.data.first()
            YouTube.visitorData = settings[VisitorDataKey]
                ?: YouTube.visitorData().getOrNull()?.also { visitorData ->
                    dataStore.edit { it[VisitorDataKey] = visitorData }
                }
        }
    }

    /** Imports a cookies.txt pushed over adb, if any, then checks the stored cookie against YouTube. */
    fun refreshAccount() {
        scope.launch {
            accountLock.withLock {
                importCookieFile()
                val cookie = dataStore.data.first()[CookieKey]
                _account.value =
                    if (cookie == null) {
                        AccountState.SignedOut
                    } else {
                        runCatching { YouTube.cookie = cookie }
                            .mapCatching { YouTube.accountInfo().getOrThrow() }
                            .fold(
                                onSuccess = {
                                    scope.launch { loadLikedSongs() }
                                    AccountState.SignedIn(it.name)
                                },
                                onFailure = {
                                    Timber.w(it, "Stored cookie was rejected")
                                    AccountState.Failed
                                },
                            )
                    }
            }
        }
    }

    // adb can write to the app's external files dir without root; the file is deleted once read.
    private suspend fun importCookieFile() {
        val file = File(getExternalFilesDir(null), COOKIE_FILE)
        if (!file.exists()) return
        val cookie = parseCookie(file.readText())
        file.delete()
        if (cookie.isBlank()) {
            Timber.w("$COOKIE_FILE had no YouTube cookies")
            return
        }
        dataStore.edit { it[CookieKey] = cookie }
    }

    private companion object {
        const val COOKIE_FILE = "cookies.txt"
        const val PLAYER_CACHE_BYTES = 256L * 1024 * 1024
    }
}

private object NoTokenProvider : TokenProvider {
    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?,
    ): PoTokenResult? = null
}

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
