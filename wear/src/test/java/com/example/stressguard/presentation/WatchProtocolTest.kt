package com.example.stressguard.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchProtocolTest {
    @Test
    fun phoneState_decodesWithoutChangingValues() {
        val payload = "1|READY|36|NORMAL|LOW|1234|1|2345|3456|ACTIVE|42|1"
        val state = WatchProtocol.State.decode(payload)!!
        assertEquals(36, state.score)
        assertEquals("NORMAL", state.label)
        assertEquals(WatchProtocol.Severity.LOW, state.severity)
        assertEquals(42L, state.feedbackId)
        assertEquals(true, state.alertActive)
    }

    @Test
    fun unsupportedOrIncompleteState_isRejected() {
        assertNull(WatchProtocol.State.decode("2|READY|36"))
        assertNull(WatchProtocol.State.decode("1|READY"))
    }
}
