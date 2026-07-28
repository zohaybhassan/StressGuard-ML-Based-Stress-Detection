package com.example.stressguard.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.stressguard.SessionManager
import com.example.stressguard.data.local.AlertEventEntity
import com.example.stressguard.data.local.HealthChecklistEntity
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.data.local.StressPredictionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Signing out has to actually remove the previous user's data.
 *
 * This test exists because it did not. `RoomDatabase.clearAllTables` is blocking and calls Room's
 * own `assertNotMainThread`, so running it from a coroutine on the main dispatcher threw every
 * time; the throw was swallowed into a warning and the database survived sign-out untouched. The
 * next account to sign in on the device inherited the previous one's charts, prediction history and
 * health checklist — the last of which is a list of medical conditions.
 *
 * Nothing caught it because every *other* store in the wipe clears fine from the main thread, so the
 * profile really did disappear and the sign-out looked like it had worked.
 *
 * Instrumented rather than a unit test on purpose: the defect was entirely about which thread the
 * call ran on, which a fake database would not have reproduced.
 */
@RunWith(AndroidJUnit4::class)
class LocalUserDataTest {

    private lateinit var database: StressGuardDatabase
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        // Deliberately *not* allowMainThreadQueries: that would mask the exact failure this covers.
        database = Room.inMemoryDatabaseBuilder(context, StressGuardDatabase::class.java).build()
        StressGuardDatabase.overrideForTest(database)
    }

    @After
    fun tearDown() {
        StressGuardDatabase.overrideForTest(null)
        database.close()
        SessionManager.clear(context)
    }

    private suspend fun seed() {
        database.stressPredictions().insert(
            StressPredictionEntity(
                recordedAtEpochMs = now,
                label = "stressed",
                classIndex = 1,
                confidence = 0.9f,
                probabilities = listOf(0.1f, 0.9f),
                modelVersion = "Voting_top3_tuned/binary",
                heartRate = 96,
                dailySteps = 3100,
                activityLevel = 8000,
                sleepHours = 5.9f,
                outOfTrainingRange = false,
            )
        )
        database.alertEvents().insert(
            AlertEventEntity(
                firedAtEpochMs = now,
                reason = "3 of the last 5 readings indicated high stress",
                highCountInWindow = 3,
                windowSize = 5,
                modelVersion = "Voting_top3_tuned/binary",
            )
        )
        database.healthChecklists().save(
            HealthChecklistEntity(smoking = true, heartCondition = true, updatedAtEpochMs = now)
        )
        database.dailyStepTotals().upsertMax("2026-07-27", 9000, now)
    }

    /**
     * The regression itself: called the way the app calls it — from the main dispatcher — the wipe
     * must still empty the database.
     */
    @Test
    fun signOutClearsTheDatabaseEvenWhenCalledFromTheMainThread() = runTest {
        seed()
        assertEquals(1, database.stressPredictions().count())

        val cleared = withContext(Dispatchers.Main) { LocalUserData.clear(context) }

        assertTrue("clear() reported failure", cleared)
        assertEquals("prediction history survived sign-out", 0, database.stressPredictions().count())
        assertEquals("alert history survived sign-out", 0, database.alertEvents().count())
        assertEquals("day totals survived sign-out", 0, database.dailyStepTotals().count())
        assertNull(
            "the previous user's medical answers survived sign-out",
            database.healthChecklists().current(),
        )
    }

    /** The profile is what the model builds its feature vector from, so it has to go too. */
    @Test
    fun signOutClearsTheStoredProfile() = runTest {
        SessionManager.saveProfile(
            context = context,
            name = "Previous User",
            age = 44,
            gender = "Female",
            occupation = "Nurse",
            bmi = "Normal",
        )
        SessionManager.setLastUserId(context, "user-one")
        assertTrue(SessionManager.isProfileComplete(context))

        withContext(Dispatchers.Main) { LocalUserData.clear(context) }

        assertNull(SessionManager.readProfile(context))
        assertNull(
            "the owning account must be forgotten with the data",
            SessionManager.getLastUserId(context),
        )
    }

    @Test
    fun pendingUploadCountSeesEveryQueue() = runTest {
        seed()

        // One prediction, one alert, one checklist. Day totals are deliberately never synced.
        assertEquals(3, LocalUserData.pendingUploadCount(context))
    }

    @Test
    fun clearingAnAlreadyEmptyStoreSucceeds() = runTest {
        assertTrue(withContext(Dispatchers.Main) { LocalUserData.clear(context) })
        assertEquals(0, LocalUserData.pendingUploadCount(context))
    }
}
