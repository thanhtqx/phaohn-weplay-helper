package com.phaohn.spyhelper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordMatcherTest {

    @Test
    fun matchesExactCaseSensitive() {
        assertTrue(WordMatcher.matches("Phở", "Phở"))
        assertFalse(WordMatcher.matches("Phở", "phở"))
        assertFalse(WordMatcher.matches("Phở", "Phở "))
        assertFalse(WordMatcher.matches("Phở", " Phở"))
    }

    @Test
    fun duplicateDetectsSwappedButNotCaseVariant() {
        assertTrue(
            WordMatcher.isDuplicatePair("A", "B", "B", "A"),
        )
        assertFalse(
            WordMatcher.isDuplicatePair("A", "B", "a", "b"),
        )
    }
}