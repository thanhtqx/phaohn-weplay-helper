package com.phaohn.spyhelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WePlayVoteHelperTest {

    @Test
    fun parseTimerSeconds_handlesCommonFormats() {
        assertEquals(0, WePlayVoteHelper.parseTimerSeconds("0s"))
        assertEquals(0, WePlayVoteHelper.parseTimerSeconds("0 s"))
        assertEquals(1, WePlayVoteHelper.parseTimerSeconds("1s"))
        assertEquals(11, WePlayVoteHelper.parseTimerSeconds("11s"))
    }

    @Test
    fun parseTimerSeconds_rejectsInvalid() {
        assertNull(WePlayVoteHelper.parseTimerSeconds("--"))
        assertNull(WePlayVoteHelper.parseTimerSeconds(""))
        assertNull(WePlayVoteHelper.parseTimerSeconds("abc"))
    }
}