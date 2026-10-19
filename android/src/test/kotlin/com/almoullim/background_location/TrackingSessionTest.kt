package com.almoullim.background_location

import org.junit.Assert.*
import org.junit.Test

class TrackingSessionTest {
    @Test fun startsWaitForSubscriptionAndCoalesce() {
        val events = mutableListOf<String>()
        val replies = mutableListOf<String?>()
        var starts = 0
        val session = TrackingSession({}, { state, _ -> events.add(state) })
        repeat(2) { session.start({ replies.add(it) }, { starts++ }) }
        assertEquals(1, starts)
        assertTrue(replies.isEmpty())
        session.ready(session.token)
        assertEquals(listOf<String?>(null, null), replies)
        assertEquals(listOf("starting", "running"), events)
        session.start({ replies.add(it) }, { starts++ })
        assertEquals(1, starts)
        assertEquals(3, replies.size)
    }

    @Test fun stopDuringBindingCancelsAllWaitersAndIgnoresLateCallbacks() {
        var cleanup = 0
        val replies = mutableListOf<String?>()
        val session = TrackingSession({ cleanup++ }, { _, _ -> })
        session.start({ replies.add(it) }, {})
        val oldToken = session.token
        session.stop()
        assertEquals(listOf("cancelled"), replies)
        assertEquals(1, cleanup)
        session.start({ replies.add(it) }, {})
        session.ready(oldToken)
        session.fail(oldToken, "late_error")
        assertEquals("starting", session.state)
        assertEquals(1, replies.size)
        session.ready(session.token)
        assertEquals(listOf("cancelled", null), replies)
    }

    @Test fun failedStartupCanRetryAndRuntimeRevocationCleansUp() {
        var cleanup = 0
        val errors = mutableListOf<String?>()
        val session = TrackingSession({ cleanup++ }, { _, code -> errors.add(code) })
        var reply: String? = null
        session.start({ reply = it }, {})
        session.fail(session.token, "permission_denied")
        assertEquals("permission_denied", reply)
        session.start({}, {})
        session.ready(session.token)
        session.fail(session.token, "permission_denied")
        assertEquals("error", session.state)
        assertEquals(2, cleanup)
        assertEquals("permission_denied", errors.last())
    }

    @Test fun stopBeforeStartAndRepeatedStopRemainStopped() {
        val session = TrackingSession({}, { _, _ -> })
        session.stop(); session.stop()
        assertEquals("stopped", session.state)
        session.start({}, {})
        session.ready(session.token)
        assertEquals("running", session.state)
    }
}
