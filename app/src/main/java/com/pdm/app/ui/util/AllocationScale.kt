package com.pdm.app.ui.util

import kotlin.math.ln

/**
 * How long a usage bar's track is, as a share of the row, for something with a given allocation
 * when the largest thing in the same list has [largest]. The fill then shows use within that
 * track, so a 2 GiB guest at 50 % no longer looks like a 32 GiB guest at 50 %, and a 1-core
 * guest at full load no longer looks like an 8-core one.
 *
 * Logarithmic, so every doubling adds the same length: allocations span 512 MiB to hundreds of
 * GiB (or 1 to dozens of cores), and a linear scale would shrink small guests to a sliver.
 * With 32 GiB as the largest: 32 GiB fills the row, 8 GiB about three quarters, 2 GiB about
 * half, 512 MiB [MIN_SPAN]. With 8 cores as the largest: 4 cores about three quarters, 1 core
 * [MIN_SPAN].
 */
internal object AllocationScale {
    /** The shortest track: still wide enough to read a fill inside it. */
    const val MIN_SPAN = 0.3f

    /** Memory at or under this gets [MIN_SPAN]. */
    private const val MEMORY_FLOOR_BYTES = 512.0 * 1024 * 1024

    /** One core (or less) gets [MIN_SPAN]. */
    private const val CORE_FLOOR = 1.0

    /** Storage pools at or under 8 GiB get [MIN_SPAN]. */
    private const val STORAGE_FLOOR_BYTES = 8.0 * 1024 * 1024 * 1024

    fun span(allocated: Long?, largest: Long?): Float =
        span(allocated?.toDouble(), largest?.toDouble(), MEMORY_FLOOR_BYTES)

    fun cores(allocated: Double?, largest: Double?): Float = span(allocated, largest, CORE_FLOOR)

    fun storage(total: Long?, largest: Long?): Float =
        span(total?.toDouble(), largest?.toDouble(), STORAGE_FLOOR_BYTES)

    private fun span(allocated: Double?, largest: Double?, floor: Double): Float {
        if (allocated == null || allocated <= 0.0 || largest == null || largest <= 0.0) return 1f
        // The largest in the list always fills the row, even when it is at or under the floor.
        if (allocated >= largest) return 1f
        val top = maxOf(largest, floor * 2)
        val a = allocated.coerceIn(floor, top)
        val t = ln(a / floor) / ln(top / floor)
        return (MIN_SPAN + (1f - MIN_SPAN) * t.toFloat()).coerceIn(MIN_SPAN, 1f)
    }
}
