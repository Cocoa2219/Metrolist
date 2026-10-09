package com.metrolist.music.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class AccompanistPositionTest {

    @Test
    fun holdsSmallBackwardCorrections() {
        assertEquals(10_000, heldPosition(10_000, 9_990, jump = false))
        assertEquals(10_016, heldPosition(10_000, 10_016, jump = false))
    }

    @Test
    fun followsJumpsAndLargeBackwardSteps() {
        assertEquals(9_990, heldPosition(10_000, 9_990, jump = true))
        assertEquals(2_000, heldPosition(10_000, 2_000, jump = false))
        assertEquals(0, heldPosition(10_000, -50, jump = true))
    }
}
