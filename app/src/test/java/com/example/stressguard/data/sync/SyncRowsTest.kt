package com.example.stressguard.data.sync

import com.example.stressguard.data.local.AlertEventEntity
import com.example.stressguard.data.local.HealthChecklistEntity
import com.example.stressguard.data.local.LatencyMetricEntity
import com.example.stressguard.data.local.StressPredictionEntity
import com.example.stressguard.data.local.StressFeedbackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping local rows to the wire shape.
 *
 * Tested because the two sides disagree deliberately — the local rows carry `id` and `synced`,
 * which mean nothing on the server, and the server wants ISO timestamps where Room holds epoch
 * millis. A mistake here is invisible locally and only shows up as wrong data in Supabase.
 */
class SyncRowsTest {

    private val userId = "8f14e45f-ceea-467a-9575-1c4b1e1cf4c1"

    /** 2026-07-26 09:00:00 UTC. */
    private val recordedAt = 1785056400000L

    @Test
    fun `a prediction maps every column the server expects`() {
        val entity = StressPredictionEntity(
            id = 42,
            recordedAtEpochMs = recordedAt,
            label = "stressed",
            classIndex = 1,
            confidence = 0.908f,
            probabilities = listOf(0.092f, 0.908f),
            modelVersion = "binary-2026-07-24",
            heartRate = 84,
            dailySteps = 458,
            activityLevel = 8000,
            sleepHours = 7.5f,
            outOfTrainingRange = false,
            synced = false,
        )

        val row = StressPredictionRow.from(entity, userId)

        assertEquals(userId, row.userId)
        assertEquals("2026-07-26T09:00:00Z", row.recordedAt)
        assertEquals("stressed", row.label)
        assertEquals(1, row.classIndex)
        assertEquals(listOf(0.092f, 0.908f), row.probabilities)
        assertEquals("binary-2026-07-24", row.modelVersion)
        assertEquals(84, row.heartRate)
        // Both figures travel: what the watch measured, and what the model was told.
        assertEquals(458, row.dailySteps)
        assertEquals(8000, row.activityLevel)
        assertEquals(false, row.outOfTrainingRange)
    }

    @Test
    fun `a latency sample with no alert sends a null rather than a zero`() {
        val entity = LatencyMetricEntity(
            id = 7,
            recordedAtEpochMs = recordedAt,
            preprocessingMs = 3,
            inferenceMs = 38,
            uiUpdateMs = 1,
            receiveToPredictionMs = 41,
            // Most samples fire no alert. Zero would read as "alerted instantly" and drag the
            // reported prediction-to-alert average toward nothing.
            predictionToAlertMs = null,
            totalMs = 42,
            coldStart = false,
        )

        val row = LatencyMetricRow.from(entity, userId)

        assertNull(row.predictionToAlertMs)
        assertEquals(41, row.receiveToPredictionMs)
        assertEquals(false, row.coldStart)
        assertEquals("2026-07-26T09:00:00Z", row.recordedAt)
    }

    @Test
    fun `a cold start is carried across so averages can exclude it`() {
        val entity = LatencyMetricEntity(
            id = 1,
            recordedAtEpochMs = recordedAt,
            preprocessingMs = 4,
            inferenceMs = 1300,
            uiUpdateMs = 2,
            receiveToPredictionMs = 1330,
            predictionToAlertMs = 12,
            totalMs = 1342,
            coldStart = true,
        )

        val row = LatencyMetricRow.from(entity, userId)

        assertEquals(true, row.coldStart)
        assertEquals(12L, row.predictionToAlertMs)
    }

    @Test
    fun `an alert keeps the human-readable reason`() {
        val entity = AlertEventEntity(
            id = 3,
            firedAtEpochMs = recordedAt,
            reason = "3 of the last 5 readings indicated high stress",
            highCountInWindow = 3,
            windowSize = 5,
            modelVersion = "binary-2026-07-24",
            dismissed = false,
        )

        val row = AlertEventRow.from(entity, userId)

        assertEquals("2026-07-26T09:00:00Z", row.firedAt)
        assertEquals("3 of the last 5 readings indicated high stress", row.reason)
        assertEquals(3, row.highCountInWindow)
        assertEquals(5, row.windowSize)
    }

    @Test
    fun `sub-second timestamps survive the conversion`() {
        // The unique constraint that makes retries idempotent is (user_id, recorded_at), so
        // truncating to whole seconds would collapse distinct readings into one another.
        val entity = AlertEventEntity(
            id = 4,
            firedAtEpochMs = recordedAt + 123,
            reason = "x",
            highCountInWindow = 3,
            windowSize = 5,
            modelVersion = "v",
        )

        assertEquals("2026-07-26T09:00:00.123Z", AlertEventRow.from(entity, userId).firedAt)
    }

    @Test
    fun `a checklist maps every answer the server expects`() {
        val entity = HealthChecklistEntity(
            smoking = true,
            heartCondition = false,
            hypertension = true,
            diabetes = false,
            sleepDisorder = true,
            anxietyHistory = false,
            highCaffeineUse = true,
            physicallyInactive = false,
            updatedAtEpochMs = recordedAt,
            synced = false,
        )

        val row = HealthChecklistRow.from(entity, userId)

        assertEquals(userId, row.userId)
        assertEquals("2026-07-26T09:00:00Z", row.updatedAt)
        assertTrue(row.smoking)
        assertFalse(row.heartCondition)
        assertTrue(row.hypertension)
        assertFalse(row.diabetes)
        // Unscored by the risk table, but still carried: dropping them from the wire shape would
        // lose the answers the user actually gave.
        assertTrue(row.sleepDisorder)
        assertTrue(row.highCaffeineUse)
        assertFalse(row.physicallyInactive)
    }

    /** A checklist pulled back after a reinstall has to reconstruct the local row exactly. */
    @Test
    fun `a checklist round-trips through the wire shape`() {
        val original = HealthChecklistEntity(
            smoking = true,
            heartCondition = true,
            hypertension = false,
            diabetes = true,
            sleepDisorder = false,
            anxietyHistory = true,
            highCaffeineUse = false,
            physicallyInactive = true,
            updatedAtEpochMs = recordedAt,
            synced = false,
        )

        val restored = HealthChecklistRow.from(original, userId).toEntity()

        // Marked synced on the way back: it came from the server, so re-uploading it would push a
        // fresher updated_at over the answer's real age.
        assertEquals(original.copy(synced = true), restored)
    }

    @Test
    fun `completed feedback keeps the alert-time training snapshot`() {
        val entity = StressFeedbackEntity(
            id = 9,
            alertEventId = 3,
            alertFiredAtEpochMs = recordedAt + 500,
            predictionRecordedAtEpochMs = recordedAt,
            predictedLabel = "stressed",
            predictedClassIndex = 1,
            confidence = 0.91f,
            probabilities = listOf(0.09f, 0.91f),
            modelVersion = "binary-v1",
            heartRate = 96,
            dailySteps = 1200,
            activityLevel = 6400,
            sleepHours = 6.5f,
            outOfTrainingRange = false,
            profileAge = 22,
            profileGender = "Male",
            profileOccupation = "Student",
            profileBmi = "Normal",
            confirmedStressed = true,
            severity = 8,
            respondedAtEpochMs = recordedAt + 60_000,
        )

        val row = StressFeedbackRow.from(entity, userId)

        assertEquals("high_stress_alert", row.promptSource)
        assertEquals("2026-07-26T09:00:00Z", row.predictionRecordedAt)
        assertEquals(96, row.heartRate)
        assertEquals(6400, row.activityLevel)
        assertTrue(row.confirmedStressed)
        assertEquals(8, row.severity)
    }
}
