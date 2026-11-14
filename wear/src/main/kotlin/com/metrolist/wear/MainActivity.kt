package com.metrolist.wear

import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.NavHostController
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
import androidx.wear.compose.foundation.SwipeToDismissValue
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.pager.PagerState
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.VerticalPagerScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.currentBackStackEntryAsState
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val PAGE_ARTWORK = 0
private const val PAGE_PLAYER = 1
private const val PAGE_HOME = 2
private const val ROUTE_MAIN = "main"
private const val ROUTE_LYRICS = "lyrics"
private const val ROUTE_LYRICS_MENU = "lyrics/menu"
private const val ROUTE_LYRICS_SOURCES = "lyrics/sources"
private const val PLAYER_DIM = 0.55f
private const val ARRIVE_FADE_MS = 250
private const val LEAVE_FADE_FRACTION = 0.35f
private const val DRIFT = 0.15f

class MainActivity : ComponentActivity() {
    private val app get() = application as WearApp
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    private var ambientDetails by mutableStateOf<AmbientLifecycleObserver.AmbientDetails?>(null)
    private var ambientMinute by mutableIntStateOf(0)
    private val ambientObserver =
        AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    this@MainActivity.ambientDetails = ambientDetails
                }

                override fun onExitAmbient() {
                    ambientDetails = null
                }

                override fun onUpdateAmbient() {
                    ambientMinute++
                }
            },
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        setContent {
            var exiting by remember { mutableStateOf(false) }
            val navController = rememberSwipeDismissableNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val atRoot = backStackEntry?.destination?.route.let { it == null || it == ROUTE_MAIN }
            val swipeState = rememberSwipeToDismissBoxState()
            LaunchedEffect(swipeState.currentValue) {
                if (swipeState.currentValue == SwipeToDismissValue.Dismissed) {
                    if (atRoot) {
                        exiting = true
                        exitAfterSwipe()
                    } else {
                        navController.popBackStack()
                        // Keep the outgoing screen offscreen until the nav host finishes its pop animation.
                        navController.currentBackStackEntry?.lifecycle?.currentStateFlow?.first {
                            it == Lifecycle.State.RESUMED
                        }
                        swipeState.snapTo(SwipeToDismissValue.Default)
                    }
                }
            }
            MetrolistWearTheme {
                // Swipe-right is done here because the window's is off in the theme, so it yields to drags the app
                // has claimed, like the seek dot. That also turns off the nav host's swipe-back on Wear OS 6, where
                // it rides on the window's gesture, so this box goes back a screen too.
                // At the root it exits: it wraps the scaffold so the black background leaves with the app, revealing
                // what's underneath the translucent window, and once dismissed nothing is drawn again: the box
                // resetting would otherwise flash the app back as an afterimage while the window closes.
                if (!exiting) {
                    BasicSwipeToDismissBox(
                        state = swipeState,
                    ) { isBackground ->
                        if (!isBackground) {
                            AppScaffold { WearNavigation(app, controller, navController, ambientDetails, ambientMinute) }
                        } else if (!atRoot || swipeState.currentValue == SwipeToDismissValue.Dismissed) {
                            // Every screen sits on the song's artwork, so it stands in for the one being returned to.
                            ArtworkBackground(
                                controller?.mediaMetadata?.artworkUri,
                                dim = { PLAYER_DIM },
                                vignette = { 1f },
                                textScrim = { 0f },
                                blackout = { 0f },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val future =
            MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
                .buildAsync()
        controllerFuture = future
        future.addListener({ controller = future.get() }, mainExecutor)
    }

    override fun onResume() {
        super.onResume()
        app.refreshAccount()
    }

    // The swipe already animated the app away, so the system close animation is skipped too.
    private fun exitAfterSwipe() {
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onStop() {
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }
}

@Composable
private fun WearNavigation(
    app: WearApp,
    player: Player?,
    navController: NavHostController,
    ambient: AmbientLifecycleObserver.AmbientDetails?,
    ambientMinute: Int,
) {
    val account by app.account.collectAsState()
    val likedSongs by app.likedSongs.collectAsState()
    val nowPlaying = rememberNowPlaying(player)
    val liked = nowPlaying.mediaId in likedSongs
    val pagerState = rememberPagerState(initialPage = PAGE_PLAYER) { 3 }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    // Always-on shows only the player, so any page or screen above it gives way first.
    LaunchedEffect(ambient != null) {
        if (ambient != null) {
            navController.popBackStack(ROUTE_MAIN, inclusive = false)
            pagerState.scrollToPage(PAGE_PLAYER)
        }
    }
    // currentPage flips at the halfway point of a swipe; settledPage would only tick after the snap ends.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.drop(1).collect { haptics.tick() }
    }

    if (ambient != null) {
        AmbientPlayer(nowPlaying, liked, ambient, ambientMinute)
        return
    }

    // The nav graph is built once and keeps its route lambdas, so they must read live state, not captured values.
    val currentPlayer by rememberUpdatedState(player)
    val currentNowPlaying by rememberUpdatedState(nowPlaying)
    val currentLiked by rememberUpdatedState(liked)
    val currentAccount by rememberUpdatedState(account)

    SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_MAIN) {
        composable(ROUTE_MAIN) {
            val player = currentPlayer
            val nowPlaying = currentNowPlaying
            val liked = currentLiked
            val account = currentAccount
            val accent = rememberArtworkAccent(nowPlaying.artworkUri)
            val volume = rememberVolumeState()
            val homeListState = rememberTransformingLazyColumnState()
            val artworkDim by animateFloatAsState(if (nowPlaying.playing) 0.05f else 0.45f, label = "artworkDim")
            MainPager(
                pagerState = pagerState,
                nowPlaying = nowPlaying,
                artworkDim = { artworkDim },
                volume = volume,
                accent = accent,
                homeListState = homeListState,
            ) { page ->
                when (page) {
                    PAGE_ARTWORK -> ArtworkPage(player, nowPlaying, volume, haptics)
                    PAGE_PLAYER ->
                        PlayerPage(
                            player = player,
                            nowPlaying = nowPlaying,
                            accent = accent,
                            liked = liked,
                            onLikedChange = { nowPlaying.mediaId?.let { id -> app.setLiked(id, it) } },
                            volume = volume,
                            haptics = haptics,
                            onOpenLyrics = { navController.navigate(ROUTE_LYRICS) },
                        )
                    PAGE_HOME ->
                        HomePage(
                            account = account,
                            listState = homeListState,
                            nowPlayingId = nowPlaying.mediaId,
                            accent = accent,
                            haptics = haptics,
                            onPlay = { songs, index ->
                                if (player != null) {
                                    player.setMediaItems(songs.map { it.toMediaItem() }, index, 0L)
                                    player.prepare()
                                    player.play()
                                    scope.launch { pagerState.animateScrollToPage(PAGE_PLAYER) }
                                }
                            },
                        )
                }
            }
        }
        composable(ROUTE_LYRICS) {
            val nowPlaying = currentNowPlaying
            LyricsScreen(currentPlayer, nowPlaying, rememberArtworkAccent(nowPlaying.artworkUri), app.lyrics, haptics) {
                navController.navigate(ROUTE_LYRICS_MENU)
            }
        }
        composable(ROUTE_LYRICS_MENU) {
            LyricsMenu(currentNowPlaying, app.lyrics, haptics) { navController.navigate(ROUTE_LYRICS_SOURCES) }
        }
        composable(ROUTE_LYRICS_SOURCES) {
            LyricsSources(currentNowPlaying, app.lyrics, haptics) { navController.popBackStack(ROUTE_LYRICS, inclusive = false) }
        }
    }
}

@Composable
private fun MainPager(
    pagerState: PagerState,
    nowPlaying: NowPlaying,
    artworkDim: () -> Float,
    volume: VolumeState,
    accent: Color,
    homeListState: TransformingLazyColumnState,
    page: @Composable (Int) -> Unit,
) {
    VerticalPagerScaffold(
        pagerState = pagerState,
        pageIndicator = { PagerScrollIndicator(pagerState, homeListState) },
    ) {
        // Artwork → player: dim and vignette fade in; player → home: fades to black.
        val position = { (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 2f) }
        ArtworkBackground(
            artworkUri = nowPlaying.artworkUri,
            dim = { lerp(artworkDim(), PLAYER_DIM, position().coerceAtMost(1f)) },
            vignette = { position().coerceAtMost(1f) },
            textScrim = { (1f - position()).coerceAtLeast(0f) },
            blackout = { (position() - 1f).coerceAtLeast(0f) },
        )
        // The bezel belongs to volume on the media pages and to scrolling on home, never to paging.
        // Off-screen pages aren't composed, so a pinned, invisible page can't sit over the visible one taking touches.
        // Home → player slides in fully visible, so only arrivals from the artwork side fade in.
        var cameFrom by remember { mutableIntStateOf(pagerState.settledPage) }
        var lastSettled by remember { mutableIntStateOf(pagerState.settledPage) }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect {
                if (it != lastSettled) {
                    cameFrom = lastSettled
                    lastSettled = it
                }
            }
        }
        VerticalPager(state = pagerState, beyondViewportPageCount = 0, rotaryScrollableBehavior = null) { index ->
            // Like Spotify, artwork ↔ player don't slide or blend: the page being left fades out early in
            // the swipe, and the page arrived at fades in only once the swipe has settled.
            val settledHere = pagerState.settledPage == index
            val arrived by animateFloatAsState(
                if (settledHere || cameFrom == PAGE_HOME) 1f else 0f,
                if (settledHere && cameFrom != PAGE_HOME) tween(ARRIVE_FADE_MS) else snap(),
                label = "pageArrive",
            )
            // The side the page comes in from: above when swiping down to artwork, below when going back up.
            val arriveFrom = if (settledHere) (index - cameFrom).coerceIn(-1, 1) else 0
            Box(
                Modifier.graphicsLayer {
                    val position = position()
                    if (index <= PAGE_PLAYER && position <= PAGE_PLAYER) {
                        // Follow the finger only a little (DRIFT of the travel) so the swipe reads without a slide,
                        // and let an arriving page cover that same short distance as it fades in.
                        translationY = (position - index) * size.height * (1f - DRIFT) +
                            (1f - arrived) * arriveFrom * size.height * DRIFT
                        val leaving = (1f - abs(position - index) / LEAVE_FADE_FRACTION).coerceIn(0f, 1f)
                        alpha = arrived * leaving
                    }
                },
            ) { page(index) }
        }
        VolumeIndicator(volume, accent)
    }
}
