package com.almoullim.background_location

/** Main-thread lifecycle owner. Tokens reject callbacks from cancelled starts. */
internal class TrackingSession(
    private val cleanup: () -> Unit,
    private val emit: (String, String?) -> Unit
) {
    var state = "stopped"
        private set
    var token = 0
        private set
    private val pending = mutableListOf<(String?) -> Unit>()

    fun start(reply: (String?) -> Unit, begin: (Int) -> Unit) {
        if (state == "running") { reply(null); return }
        pending.add(reply)
        if (state == "starting") return
        token++
        state = "starting"
        emit(state, null)
        begin(token)
    }

    fun current(value: Int) = value == token && (state == "starting" || state == "running")

    fun ready(value: Int) {
        if (!current(value) || state != "starting") return
        state = "running"
        emit(state, null)
        val replies = pending.toList()
        pending.clear()
        replies.forEach { it(null) }
    }

    fun fail(value: Int, code: String) {
        if (current(value)) finish("error", code)
    }

    fun stop() = finish("stopped", "cancelled")

    private fun finish(next: String, code: String) {
        token++
        val replies = pending.toList()
        pending.clear()
        state = next
        cleanup()
        emit(next, if (next == "error") code else null)
        replies.forEach { it(code) }
    }
}
