import Foundation

internal struct TrackingConfig {
    var accuracy = "high"
    var interval = 1000
    var distance = 0.0
    var adaptive = false
    static func parse(_ args: [String: Any]) -> TrackingConfig? {
        var c = TrackingConfig()
        c.accuracy = args["accuracy"] as? String ?? "high"
        c.interval = (args["interval_ms"] as? NSNumber)?.intValue ?? 1000
        c.distance = (args["distance_filter"] as? NSNumber)?.doubleValue ?? 0
        c.adaptive = args["adaptive"] as? Bool ?? false
        guard ["high", "balanced", "lowPower"].contains(c.accuracy),
              (1000...3600000).contains(c.interval), c.distance.isFinite,
              (0...100000).contains(c.distance) else { return nil }
        return c
    }
    var dictionary: [String: Any] { ["accuracy": accuracy, "interval_ms": interval,
        "distance_filter": distance, "adaptive": adaptive] }
    func effective(stationary: Bool) -> TrackingConfig {
        var c = self
        if adaptive {
            c.distance = stationary ? max(distance, 25) : distance
            if stationary { if c.accuracy == "high" { c.accuracy = "balanced" }; c.interval = max(interval, 60000) }
        }
        return c
    }
}

/// Hysteresis and maximum downgrade time, independent of CoreMotion scheduling.
internal struct MotionPolicy {
    private(set) var stationary = false
    private var since: TimeInterval?
    private var entered: TimeInterval?
    mutating func update(still: Bool, now: TimeInterval) -> Bool {
        let before = stationary
        if !still { since = nil; entered = nil; stationary = false }
        else if stationary, let entered = entered, now - entered >= 120 {
            stationary = false; since = nil; self.entered = nil
        } else if !stationary {
            if let since = since, now - since >= 120 { stationary = true; entered = now }
            else if since == nil { since = now }
        }
        return before != stationary
    }
}
