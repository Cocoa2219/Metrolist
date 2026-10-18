/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The player's large artwork and the small one beside the title in the lyrics view are one shared
 * element, so opening or closing lyrics morphs one into the other.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalPlayerSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The lyrics/artwork transition the artwork belongs to. */
internal val LocalPlayerArtworkScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

private const val PlayerArtworkKey = "player_artwork"

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.playerArtwork(scope: AnimatedVisibilityScope? = LocalPlayerArtworkScope.current): Modifier {
    val shared = LocalPlayerSharedTransition.current ?: return this
    if (scope == null) return this
    return with(shared) {
        this@playerArtwork.sharedElement(rememberSharedContentState(PlayerArtworkKey), scope)
    }
}
