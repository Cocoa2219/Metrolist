package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeThumbnailInterceptorTest {

    @Test
    fun `hqdefault falls back through maxres then sd, dropping the signed query`() {
        assertEquals(
            listOf(
                "https://i.ytimg.com/vi/jNQXAC9IVRw/maxresdefault.jpg",
                "https://i.ytimg.com/vi/jNQXAC9IVRw/sddefault.jpg",
            ),
            higherResThumbnailCandidates("https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg?sqp=-oaymwE&rs=AOn4"),
        )
    }

    @Test
    fun `sddefault only tries maxres`() {
        assertEquals(
            listOf("https://i.ytimg.com/vi/abc/maxresdefault.jpg"),
            higherResThumbnailCandidates("https://i.ytimg.com/vi/abc/sddefault.jpg"),
        )
    }

    @Test
    fun `non video thumbnails are left alone`() {
        assertEquals(emptyList<String>(), higherResThumbnailCandidates("https://i.ytimg.com/vi/abc/maxresdefault.jpg"))
        assertEquals(emptyList<String>(), higherResThumbnailCandidates("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"))
    }
}
