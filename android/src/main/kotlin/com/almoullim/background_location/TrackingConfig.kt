package com.almoullim.background_location

internal data class TrackingConfig(val accuracy: String = "high", val interval: Long = 1000,
    val distance: Double = 0.0, val adaptive: Boolean = false) {
    fun toMap() = mapOf("accuracy" to accuracy, "interval_ms" to interval,
        "distance_filter" to distance, "adaptive" to adaptive)
    fun stationary() = copy(accuracy = if (accuracy == "high") "balanced" else accuracy, interval = maxOf(interval, 60000), distance = maxOf(distance, 25.0))
    companion object {
        fun parse(args: Map<*, *>): TrackingConfig {
            val c = TrackingConfig(args["accuracy"] as? String ?: "high",
                (args["interval_ms"] as? Number)?.toLong() ?: 1000,
                (args["distance_filter"] as? Number)?.toDouble() ?: 0.0,
                args["adaptive"] as? Boolean ?: false)
            require(c.accuracy in listOf("high", "balanced", "lowPower") && c.interval in 1000..3600000 &&
                c.distance.isFinite() && c.distance in 0.0..100000.0)
            return c
        }
    }
}
