import Foundation
import CoreLocation

var cleanupCount = 0
var events: [String] = []
var replies: [String] = []
let lifecycle = TrackingLifecycle(cleanup: { cleanupCount += 1 }, emit: { state, _ in events.append(state) })
var starts = 0
lifecycle.start(reply: { replies.append($0 ?? "success") }, begin: { _ in starts += 1 })
lifecycle.start(reply: { replies.append($0 ?? "success") }, begin: { _ in starts += 1 })
assert(starts == 1 && replies.isEmpty)
let cancelledToken = lifecycle.token
lifecycle.stop()
assert(replies == ["cancelled", "cancelled"] && cleanupCount == 1)
lifecycle.start(reply: { replies.append($0 ?? "success") }, begin: { _ in starts += 1 })
lifecycle.ready(cancelledToken)
lifecycle.fail(cancelledToken, code: "late_error")
assert(lifecycle.state == "starting" && replies.count == 2)
lifecycle.ready(lifecycle.token)
assert(replies.last == "success" && lifecycle.state == "running")
lifecycle.start(reply: { replies.append($0 ?? "success") }, begin: { _ in starts += 1 })
assert(starts == 2 && replies.count == 4)
lifecycle.fail(lifecycle.token, code: "permission_denied")
assert(lifecycle.state == "error" && cleanupCount == 2)
lifecycle.stop(); lifecycle.stop()
lifecycle.start(reply: { replies.append($0 ?? "success") }, begin: { _ in starts += 1 })
lifecycle.fail(lifecycle.token, code: "startup_timeout")
assert(replies.last == "startup_timeout")
print("Swift lifecycle regression scenarios passed")

assert(LocationConfiguration.desiredAccuracy("high") == kCLLocationAccuracyBest)
assert(LocationConfiguration.desiredAccuracy("balanced") == kCLLocationAccuracyHundredMeters)
assert(LocationConfiguration.desiredAccuracy("lowPower") == kCLLocationAccuracyKilometer)
let measured = CLLocation(coordinate: CLLocationCoordinate2D(latitude: 1, longitude: 2),
    altitude: 5, horizontalAccuracy: 3, verticalAccuracy: 4, course: 90,
    courseAccuracy: 2, speed: 10, speedAccuracy: 0.5, timestamp: Date())
let metadata = LocationConfiguration.metadata(measured)
assert(metadata["speed_accuracy"] as? Double == 0.5)
assert(metadata["vertical_accuracy"] as? Double == 4)
assert(metadata["bearing_accuracy"] as? Double == 2)
assert(metadata["provider"] is NSNull)
let missing = CLLocation(coordinate: CLLocationCoordinate2D(latitude: 1, longitude: 2),
    altitude: 5, horizontalAccuracy: 3, verticalAccuracy: -1, course: 90,
    courseAccuracy: -1, speed: 10, speedAccuracy: -1, timestamp: Date())
let unknown = LocationConfiguration.metadata(missing)
assert(unknown["speed_accuracy"] is NSNull)
assert(unknown["vertical_accuracy"] is NSNull)
assert(unknown["bearing_accuracy"] is NSNull)
print("Swift accuracy configuration and metadata scenarios passed")

assert(TrackingConfig.parse(["interval_ms": 0]) == nil)
assert(TrackingConfig.parse(["accuracy": "bad"]) == nil)
assert(TrackingConfig.parse(["distance_filter": Double.nan]) == nil)
let sharing = TrackingConfig.parse(["interval_ms": 15000, "adaptive": true])!
assert(sharing.effective(stationary: false).accuracy == "high")
assert(sharing.effective(stationary: true).accuracy == "balanced")
assert(sharing.effective(stationary: true).distance == 25)
assert(TrackingConfig().effective(stationary: true).accuracy == "high")
assert(TrackingConfig().effective(stationary: true).distance == 0)
var motion = MotionPolicy()
assert(!motion.update(still: true, now: 0))
assert(!motion.update(still: true, now: 119))
assert(motion.update(still: true, now: 120) && motion.stationary)
assert(motion.update(still: false, now: 121) && !motion.stationary)
assert(!motion.update(still: true, now: 122))
assert(motion.update(still: true, now: 242))
assert(motion.update(still: true, now: 362) && !motion.stationary)
print("Swift adaptive profile, hysteresis, motion recovery and validation scenarios passed")

let stationaryTime = Date()
let stoppedFix = CLLocation(coordinate: CLLocationCoordinate2D(latitude: 1, longitude: 2), altitude: 0,
    horizontalAccuracy: 5, verticalAccuracy: 5, course: 0, speed: 0, timestamp: stationaryTime)
assert(LocationConfiguration.isStopped(stoppedFix, now: stationaryTime))
assert(!LocationConfiguration.isStopped(stoppedFix, now: stationaryTime.addingTimeInterval(31)))
assert(!LocationConfiguration.isStopped(measured))
assert(!LocationConfiguration.isStopped(nil))
assert(TrackingConfig.parse(["accuracy": "lowPower", "adaptive": true])!.effective(stationary: true).accuracy == "lowPower")
print("Swift stationary evidence freshness and low-power preservation scenarios passed")
