package com.example.stressguard

import org.junit.Assert.assertEquals
import org.junit.Test

class StressDisplayTest {
    @Test
    fun score_usesTheSameAnchorsAsThePhoneGauge() {
        assertEquals(10, StressDisplay.score(floatArrayOf(1f, 0f, 0f)))
        assertEquals(50, StressDisplay.score(floatArrayOf(0f, 1f, 0f)))
        assertEquals(90, StressDisplay.score(floatArrayOf(0f, 0f, 1f)))
        assertEquals(36, StressDisplay.score(floatArrayOf(.5f, .35f, .15f)))
    }

    @Test
    fun stateProtocol_roundTripsAllWatchVisibleFields() {
        val expected = WatchProtocol.State(
            status = WatchProtocol.Status.READY,
            score = 67,
            label = "HIGH STRESS",
            severity = StressSeverity.HIGH,
            measuredAtEpochMs = 1234,
            outOfTrainingRange = true,
            alertsMutedUntilEpochMs = 2345,
            workoutUntilEpochMs = 3456,
            workoutStatus = WatchProtocol.WorkoutStatus.PAUSED,
            feedbackId = 42,
            alertActive = true,
        )

        assertEquals(expected, WatchProtocol.State.decode(expected.encode()))
    }

    @Test
    fun malformedProtocolPayload_isRejected() {
        assertEquals(null, WatchProtocol.State.decode("1|READY"))
        assertEquals(null, WatchProtocol.Command.decode("99|REQUEST_STATE"))
    }
}
