package com.almoullim.background_location

import android.location.Location

/** Arbitration for concurrent GPS/network callbacks. Never read cached fixes.
 * Use monotonic age so changing the device clock cannot revive a stale fix.
 */
internal class FallbackFixFilter {
    private var last: Location? = null

    fun accept(fix: Location, nowNanos: Long): Boolean {
        val age = nowNanos - fix.elapsedRealtimeNanos
        if (fix.elapsedRealtimeNanos <= 0 || age < 0 || age > 30_000_000_000L ||
            !fix.latitude.isFinite() || fix.latitude !in -90.0..90.0 ||
            !fix.longitude.isFinite() || fix.longitude !in -180.0..180.0 ||
            !fix.hasAccuracy() || !fix.accuracy.isFinite() || fix.accuracy < 0) return false
        val previous = last
        if (previous != null) {
            if (fix.elapsedRealtimeNanos <= previous.elapsedRealtimeNanos) return false
            // Keep a recent better provider fix, but allow fallback once it is
            // ten seconds old. Same-provider movement must remain observable.
            if (fix.provider != previous.provider && fix.accuracy > previous.accuracy &&
                nowNanos - previous.elapsedRealtimeNanos < 10_000_000_000L) return false
        }
        last = Location(fix)
        return true
    }
}
