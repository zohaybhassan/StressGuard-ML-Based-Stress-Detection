package com.example.stressguard.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The local store, against a real SQLite instance held in memory.
 *
 * Two behaviours here are load-bearing rather than incidental:
 *
 *  - **The unsynced queue.** Phase 7's sync worker will drain it. If `markSynced` failed to
 *    exclude rows, every sync would re-upload the whole history and duplicate it server-side.
 *  - **Retention only deleting synced rows.** A month offline must not silently discard
 *    readings that never reached the backend, which is exactly when the offline-first claim
 *    matters most.
 */
@RunWith(AndroidJUnit4::class)
class StressGuardDatabaseTest {

    private lateinit var database: StressGuardDatabase

    private val now = System.currentTimeMillis()
    private val dayMs = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            StressGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private fun prediction(
        atEpochMs: Long = now,
        classIndex: Int = 1,
        synced: Boolean = false,
    ) = StressPredictionEntity(
        recordedAtEpochMs = atEpochMs,
        label = if (classIndex == 1) "stressed" else "not_stressed",
        classIndex = classIndex,
        confidence = 0.63f,
        probabilities = listOf(0.37f, 0.63f),
        modelVersion = "Voting_top3_tuned/binary",
        heartRate = 105,
        dailySteps = 1200,
        sleepHours = 5.3f,
        outOfTrainingRange = false,
        synced = synced,
    )

    private fun latency(atEpochMs: Long = now, coldStart: Boolean = false, synced: Boolean = false) =
        LatencyMetricEntity(
            recordedAtEpochMs = atEpochMs,
            preprocessingMs = 40,
            inferenceMs = if (coldStart) 2_400 else 110,
            uiUpdateMs = 20,
            receiveToPredictionMs = if (coldStart) 2_440 else 150,
            predictionToAlertMs = null,
            totalMs = if (coldStart) 2_460 else 170,
            coldStart = coldStart,
            synced = synced,
        )

    // --- predictions -------------------------------------------------------------

    @Test
    fun aStoredPredictionComesBackIntact() = runTest {
        database.stressPredictions().insert(prediction())

        val stored = database.stressPredictions().latest(10).single()
        assertEquals("stressed", stored.label)
        assertEquals(1, stored.classIndex)
        assertEquals(0.63f, stored.confidence, 1e-6f)
        assertEquals("Voting_top3_tuned/binary", stored.modelVersion)
        assertEquals("the full distribution survives the type converter", listOf(0.37f, 0.63f), stored.probabilities)
        assertEquals(105, stored.heartRate)
    }

    @Test
    fun latestReturnsNewestFirst() = runTest {
        val dao = database.stressPredictions()
        dao.insert(prediction(atEpochMs = now - 2000))
        dao.insert(prediction(atEpochMs = now))
        dao.insert(prediction(atEpochMs = now - 1000))

        val ordered = dao.latest(3).map { it.recordedAtEpochMs }
        assertEquals(listOf(now, now - 1000, now - 2000), ordered)
    }

    @Test
    fun markSyncedRemovesRowsFromTheQueue() = runTest {
        val dao = database.stressPredictions()
        dao.insert(prediction(atEpochMs = now - 1000))
        dao.insert(prediction(atEpochMs = now))

        val queued = dao.unsynced()
        assertEquals(2, queued.size)
        assertEquals("oldest first, so the backend receives them in order", now - 1000, queued.first().recordedAtEpochMs)

        dao.markSynced(listOf(queued.first().id))

        val remaining = dao.unsynced()
        assertEquals("a re-sync must not re-upload what already landed", 1, remaining.size)
        assertEquals(now, remaining.single().recordedAtEpochMs)
    }

    @Test
    fun countOfClassSinceBacksTodaysHighStressCount() = runTest {
        val dao = database.stressPredictions()
        dao.insert(prediction(atEpochMs = now, classIndex = 1))
        dao.insert(prediction(atEpochMs = now - 1000, classIndex = 1))
        dao.insert(prediction(atEpochMs = now - 2000, classIndex = 0))
        dao.insert(prediction(atEpochMs = now - 3 * dayMs, classIndex = 1))

        assertEquals(2, dao.countOfClassSince(classIndex = 1, sinceEpochMs = now - dayMs))
        assertEquals(1, dao.countOfClassSince(classIndex = 0, sinceEpochMs = now - dayMs))
    }

    // --- retention ---------------------------------------------------------------

    @Test
    fun retentionKeepsRecentRowsAndAnythingNotYetSynced() = runTest {
        val dao = database.stressPredictions()
        dao.insert(prediction(atEpochMs = now, synced = true))
        dao.insert(prediction(atEpochMs = now - 40 * dayMs, synced = true))
        dao.insert(prediction(atEpochMs = now - 40 * dayMs, synced = false))

        database.purgeOlderThan(now - RETENTION_DAYS * dayMs)

        val remaining = dao.latest(10)
        assertEquals(2, remaining.size)
        assertTrue("recent row kept", remaining.any { it.recordedAtEpochMs == now })
        assertTrue(
            "an old row that never synced is kept, or a month offline loses data",
            remaining.any { it.recordedAtEpochMs == now - 40 * dayMs && !it.synced },
        )
    }

    // --- latency -----------------------------------------------------------------

    @Test
    fun averagesExcludeColdStarts() = runTest {
        val dao = database.latencyMetrics()
        dao.insert(latency(coldStart = true))
        dao.insert(latency(atEpochMs = now - 1000, coldStart = false))
        dao.insert(latency(atEpochMs = now - 2000, coldStart = false))

        assertEquals("only the two steady-state samples count", 2, dao.steadyStateSampleCount())
        assertEquals(
            "a 2.4 s model load must not inflate the reported average",
            150.0,
            dao.averageReceiveToPredictionMs()!!,
            0.01,
        )
        assertEquals(170.0, dao.averageTotalMs()!!, 0.01)
    }

    @Test
    fun withNoSamplesTheAveragesAreNullRatherThanZero() = runTest {
        val dao = database.latencyMetrics()

        assertNull("no data is not the same as zero latency", dao.averageReceiveToPredictionMs())
        assertNull(dao.averagePredictionToAlertMs())
        assertEquals(0, dao.steadyStateSampleCount())
    }

    @Test
    fun alertTimingAveragesOnlyOverSamplesThatFiredAnAlert() = runTest {
        val dao = database.latencyMetrics()
        dao.insert(latency())
        dao.insert(latency(atEpochMs = now - 1000).copy(predictionToAlertMs = 80))
        dao.insert(latency(atEpochMs = now - 2000).copy(predictionToAlertMs = 120))

        assertEquals(100.0, dao.averagePredictionToAlertMs()!!, 0.01)
    }

    // --- alerts ------------------------------------------------------------------

    @Test
    fun mostRecentAlertDrivesTheCooldownAcrossRestarts() = runTest {
        val dao = database.alertEvents()
        assertNull("no alerts yet means no cooldown", dao.mostRecent())

        dao.insert(alert(firedAtEpochMs = now - 60_000))
        dao.insert(alert(firedAtEpochMs = now))

        assertEquals(
            "the newest alert is what the cooldown is measured from",
            now,
            dao.mostRecent()!!.firedAtEpochMs,
        )
    }

    @Test
    fun aDismissedAlertIsStillRetainedAsHistory() = runTest {
        val dao = database.alertEvents()
        val id = dao.insert(alert())

        dao.markDismissed(id)

        val stored = dao.latest(1).single()
        assertTrue(stored.dismissed)
        assertNotNull("dismissing is not deleting", dao.mostRecent())
        assertEquals(1, dao.count())
    }

    private fun alert(firedAtEpochMs: Long = now) = AlertEventEntity(
        firedAtEpochMs = firedAtEpochMs,
        reason = "3 of the last 5 readings indicated high stress",
        highCountInWindow = 3,
        windowSize = 5,
        modelVersion = "Voting_top3_tuned/binary",
    )
}
