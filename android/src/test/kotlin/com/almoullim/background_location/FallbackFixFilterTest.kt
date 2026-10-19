package com.almoullim.background_location

import android.location.Location
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FallbackFixFilterTest {
    private fun fix(provider: String = "gps", seconds: Long = 100, precision: Float = 5f) = Location(provider).apply {
        latitude = 1.0; longitude = 2.0; accuracy = precision
        elapsedRealtimeNanos = seconds * 1_000_000_000L
    }
    @Test fun rejectsStaleFutureInvalidAndOutOfOrderFixes() {
        val filter = FallbackFixFilter()
        val now = 100_000_000_000L
        assertFalse(filter.accept(fix(seconds = 69), now))
        assertFalse(filter.accept(fix(seconds = 101), now))
        assertFalse(filter.accept(fix(precision = Float.NaN), now))
        assertFalse(filter.accept(fix().apply { removeAccuracy() }, now))
        assertFalse(filter.accept(fix().apply { latitude = 91.0 }, now))
        assertTrue(filter.accept(fix(), now))
        assertFalse(filter.accept(fix(), now))
        assertFalse(filter.accept(fix(seconds = 99), now))
    }
    @Test fun poorerNetworkFixWaitsUntilGpsIsOldButBetterFixAndMovementPass() {
        val filter = FallbackFixFilter()
        assertTrue(filter.accept(fix(), 100_000_000_000L))
        assertFalse(filter.accept(fix("network", 101, 50f), 101_000_000_000L))
        assertTrue(filter.accept(fix("gps", 102, 6f), 102_000_000_000L))
        assertTrue(filter.accept(fix("network", 112, 50f), 112_000_000_000L))
        assertTrue(filter.accept(fix("gps", 113, 5f), 113_000_000_000L))
        assertTrue(filter.accept(fix("network", 114, 4f), 114_000_000_000L))
    }
}
