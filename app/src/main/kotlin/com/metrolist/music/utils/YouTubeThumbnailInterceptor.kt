/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.size.pxOrElse
import java.util.concurrent.ConcurrentHashMap

private val YTIMG_VIDEO_THUMBNAIL_PATTERN =
    Regex("^(https://i\\.ytimg\\.com/vi/[^/]+/)(default|mqdefault|hqdefault|sddefault)\\.jpg(\\?.*)?$")

private const val LARGE_IMAGE_PX = 544

/**
 * Video thumbnails from YouTube are at most 480x360 (letterboxed) and look blurry when displayed
 * large. maxresdefault/sddefault are sharper but not published for every video, so try them in
 * order and fall back to the original URL. Variants that returned 404 are remembered so they
 * aren't requested again during this process.
 */
class YouTubeThumbnailInterceptor : Interceptor {
    private val missing = ConcurrentHashMap.newKeySet<String>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        if (!chain.size.isLarge()) return chain.proceed()

        for (candidate in higherResThumbnailCandidates(url)) {
            if (candidate in missing) continue
            when (val result = chain.withRequest(chain.request.newBuilder().data(candidate).build()).proceed()) {
                is SuccessResult -> return result
                is ErrorResult -> if ((result.throwable as? HttpException)?.response?.code == 404) missing += candidate
            }
        }
        return chain.proceed()
    }

    private fun Size.isLarge() =
        maxOf(width.pxOrElse { Int.MAX_VALUE }, height.pxOrElse { Int.MAX_VALUE }) >= LARGE_IMAGE_PX
}

internal fun higherResThumbnailCandidates(url: String): List<String> {
    val match = YTIMG_VIDEO_THUMBNAIL_PATTERN.matchEntire(url) ?: return emptyList()
    val base = match.groupValues[1]
    return if (match.groupValues[2] == "sddefault") {
        listOf("${base}maxresdefault.jpg")
    } else {
        listOf("${base}maxresdefault.jpg", "${base}sddefault.jpg")
    }
}
