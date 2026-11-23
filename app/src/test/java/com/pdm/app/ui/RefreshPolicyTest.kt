package com.pdm.app.ui

import com.pdm.app.data.repo.LoadDepth
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshPolicyTest {
    @Test
    fun backoffDoublesFromThirtySecondsAndCapsAtFiveMinutes() {
        assertEquals(30_000L, RefreshPolicy.interval(0))
        assertEquals(60_000L, RefreshPolicy.interval(1))
        assertEquals(120_000L, RefreshPolicy.interval(2))
        assertEquals(240_000L, RefreshPolicy.interval(3))
        assertEquals(300_000L, RefreshPolicy.interval(4))
        assertEquals(300_000L, RefreshPolicy.interval(40))
    }

    @Test
    fun nextLoadCountsFromTheEndOfTheLastOne() {
        assertEquals(0L, RefreshPolicy.dueIn(SyncState(), now = 5_000))
        val sync = SyncState(lastAttemptAt = 100_000)
        assertEquals(20_000L, RefreshPolicy.dueIn(sync, now = 110_000))
        // Back after an hour: due at once.
        assertEquals(true, RefreshPolicy.dueIn(sync, now = 3_700_000) <= 0)
        // Two failures in a row: two minutes.
        assertEquals(120_000L, RefreshPolicy.dueIn(sync.copy(failures = 2), now = 100_000))
    }

    @Test
    fun slowTierRunsOnPullSignInAndEveryFiveMinutes() {
        val fresh = SyncState(lastFullAt = 1_000_000)
        assertEquals(LoadDepth.FULL, RefreshPolicy.depth(RefreshReason.AUTO, SyncState(), now = 0))
        assertEquals(LoadDepth.QUICK, RefreshPolicy.depth(RefreshReason.AUTO, fresh, now = 1_030_000))
        assertEquals(LoadDepth.QUICK, RefreshPolicy.depth(RefreshReason.TIMEFRAME, fresh, now = 1_030_000))
        assertEquals(LoadDepth.QUICK, RefreshPolicy.depth(RefreshReason.ACTION, fresh, now = 1_030_000))
        assertEquals(LoadDepth.FULL, RefreshPolicy.depth(RefreshReason.PULL, fresh, now = 1_030_000))
        assertEquals(LoadDepth.FULL, RefreshPolicy.depth(RefreshReason.AUTO, fresh, now = 1_300_000))
    }

    @Test
    fun freshnessFollowsAgeAndFailures() {
        val ok = SyncState(lastSuccessAt = 0)
        assertEquals(Freshness.LOADING, RefreshPolicy.freshness(SyncState(), now = 0))
        assertEquals(Freshness.LIVE, RefreshPolicy.freshness(ok, now = 59_000))
        assertEquals(Freshness.AGING, RefreshPolicy.freshness(ok, now = 60_000))
        assertEquals(Freshness.STALE, RefreshPolicy.freshness(ok, now = 300_000))
        assertEquals(Freshness.FAILING, RefreshPolicy.freshness(ok.copy(failures = 1), now = 1_000))
        assertEquals(Freshness.CACHED, RefreshPolicy.freshness(SyncState(cachedAt = 5), now = 0))
        assertEquals(Freshness.FAILING, RefreshPolicy.freshness(SyncState(cachedAt = 5, paused = true), now = 0))
    }

    @Test
    fun pausedNeverComesDueByItself() {
        assertEquals(Long.MAX_VALUE, RefreshPolicy.dueIn(SyncState(lastAttemptAt = 0, paused = true), now = 10_000_000))
    }

    @Test
    fun ageIsShort() {
        assertEquals("0s", RefreshPolicy.age(-5))
        assertEquals("12s", RefreshPolicy.age(12_400))
        assertEquals("4m", RefreshPolicy.age(4 * 60_000 + 59_000))
        assertEquals("2h", RefreshPolicy.age(2 * 3_600_000L + 1))
        assertEquals("3d", RefreshPolicy.age(3 * 86_400_000L))
    }
}
