package com.pdm.app.ui.adaptive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {
    @Test
    fun twoPaneKicksInAtSixHundredDp() {
        assertFalse(isOperatorTwoPane(320))
        assertFalse(isOperatorTwoPane(599))
        assertTrue(isOperatorTwoPane(600))
        assertTrue(isOperatorTwoPane(800))
    }
}
