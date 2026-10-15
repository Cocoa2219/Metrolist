package com.metrolist.wearprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class ParseCookieTest {
    @Test
    fun netscapeKeepsOnlyYouTubeRows() {
        val file =
            """
            # Netscape HTTP Cookie File
            .youtube.com	TRUE	/	TRUE	1999999999	SAPISID	abc
            #HttpOnly_.youtube.com	TRUE	/	TRUE	1999999999	__Secure-3PSID	def
            .google.com	TRUE	/	TRUE	1999999999	NID	skip
            """.trimIndent()
        assertEquals("SAPISID=abc; __Secure-3PSID=def", parseCookie(file))
    }

    @Test
    fun rawHeaderIsTrimmed() {
        assertEquals("SAPISID=abc; HSID=x", parseCookie("Cookie: SAPISID=abc; HSID=x\n"))
    }

    @Test
    fun netscapeWithoutYouTubeIsEmpty() {
        assertEquals("", parseCookie(".google.com\tTRUE\t/\tTRUE\t0\tNID\tx"))
    }
}
