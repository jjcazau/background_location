import Foundation

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
