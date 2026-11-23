package com.pdm.app.ui.util

/** Three taps in a row within [windowMs] unlock a hidden control. */
class TripleTapUnlock(private val windowMs: Long = 1_200L) {
    private var count = 0
    private var lastAt = 0L

    fun tap(nowMs: Long): Boolean {
        count = if (lastAt > 0L && nowMs - lastAt in 1 until windowMs) count + 1 else 1
        lastAt = nowMs
        return count >= 3
    }
}
