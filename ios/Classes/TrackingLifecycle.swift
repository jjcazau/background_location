import Foundation

// Main-thread lifecycle state, isolated from CoreLocation for deterministic tests.
final class TrackingLifecycle {
    private(set) var state = "stopped"
    private(set) var token = 0
    private var replies: [(String?) -> Void] = []
    let cleanup: () -> Void
    let emit: (String, String?) -> Void

    init(cleanup: @escaping () -> Void, emit: @escaping (String, String?) -> Void) {
        self.cleanup = cleanup
        self.emit = emit
    }

    func start(reply: @escaping (String?) -> Void, begin: (Int) -> Void) {
        if state == "running" { reply(nil); return }
        replies.append(reply)
        if state == "starting" { return }
        token += 1
        state = "starting"
        emit(state, nil)
        begin(token)
    }

    func current(_ value: Int) -> Bool {
        return value == token && (state == "starting" || state == "running")
    }

    func ready(_ value: Int) {
        guard current(value), state == "starting" else { return }
        state = "running"
        emit(state, nil)
        let pending = replies
        replies.removeAll()
        pending.forEach { $0(nil) }
    }

    func fail(_ value: Int, code: String) {
        if current(value) { finish(state: "error", code: code) }
    }

    func stop() { finish(state: "stopped", code: "cancelled") }

    private func finish(state: String, code: String) {
        token += 1
        let pending = replies
        replies.removeAll()
        self.state = state
        cleanup()
        emit(state, state == "error" ? code : nil)
        pending.forEach { $0(code) }
    }
}
