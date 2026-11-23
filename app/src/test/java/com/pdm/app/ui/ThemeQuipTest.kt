package com.pdm.app.ui

import kotlin.random.Random
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeQuipTest {
    @Test
    fun theRequestedLinesAreInThePool() {
        assertTrue(LIGHT_MODE_QUIPS.containsAll(listOf("Ew.", "Y?", "Yuck..", "U ok?")))
    }

    @Test
    fun neverTheSameQuipTwiceInARow() {
        val random = Random(7)
        var last: String? = null
        repeat(200) {
            val next = pickQuip(last, random)
            assertNotEquals(last, next)
            last = next
        }
    }
}
