package com.example.stressguard.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The latest reading from the watch, and a stream of them.
 *
 * `VitalReceiverService` and the dashboard live in the same process, so a shared object with a
 * [StateFlow] replaces the `STRESS_DATA_UPDATE` broadcast that previously carried two strings
 * between them. That removes the re-parsing on the receiving side, the dynamic receiver
 * registration in the Activity's `onResume`/`onPause`, and the window where a reading arriving
 * while the dashboard was paused was simply lost.
 *
 * Being a `StateFlow` also means a collector attaching later still gets the most recent
 * reading, so rotating the screen no longer blanks the vitals until the watch next sends.
 */
object SensorRepository {

    private val _latest = MutableStateFlow<SensorReading?>(null)

    /** Most recent valid reading, or null before the first one arrives. */
    val latest: StateFlow<SensorReading?> = _latest.asStateFlow()

    /** Count of readings discarded as implausible, surfaced for diagnostics. */
    @Volatile
    var rejectedCount: Int = 0
        private set

    fun publish(reading: SensorReading) {
        _latest.value = reading
    }

    fun recordRejected() {
        rejectedCount++
    }

    /** Test hook. Not used in production code. */
    fun reset() {
        _latest.value = null
        rejectedCount = 0
    }
}
