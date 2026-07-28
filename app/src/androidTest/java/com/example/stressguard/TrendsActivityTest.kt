package com.example.stressguard

import android.view.View
import android.widget.TextView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.stressguard.data.local.StressGuardDatabase
import com.example.stressguard.data.local.StressPredictionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The trends screen, on a real device.
 *
 * Worth an instrumented test rather than a manual tap: it is the first screen with a third-party
 * charting library, so the failures it can produce — a missing view id, an unresolved
 * `com.github.mikephil` class, a style attribute the theme does not carry — all happen at inflation
 * time and none of them are visible from a unit test. Launching the Activity at all is most of the
 * assertion.
 *
 * Both data states are covered because with passive collection the sparse one is routine, not an
 * edge case: the watch is not worn every day.
 */
@RunWith(AndroidJUnit4::class)
class TrendsActivityTest {

    private lateinit var database: StressGuardDatabase

    private val now = System.currentTimeMillis()
    private val dayMs = TimeUnit.DAYS.toMillis(1)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            StressGuardDatabase::class.java,
        ).allowMainThreadQueries().build()
        StressGuardDatabase.overrideForTest(database)
    }

    @After
    fun tearDown() {
        StressGuardDatabase.overrideForTest(null)
        database.close()
    }

    private fun prediction(atEpochMs: Long, classIndex: Int) = StressPredictionEntity(
        recordedAtEpochMs = atEpochMs,
        label = if (classIndex == 1) "stressed" else "not_stressed",
        classIndex = classIndex,
        confidence = 0.8f,
        probabilities = listOf(0.2f, 0.8f),
        modelVersion = "Voting_top3_tuned/binary",
        heartRate = 78,
        dailySteps = 5200,
        activityLevel = 8400,
        sleepHours = 7.1f,
        outOfTrainingRange = false,
    )

    /** Seeds [days] separate days, each carrying [highPerDay] high-stress readings. */
    private fun seed(days: Int, highPerDay: Int) = runBlocking {
        val dao = database.stressPredictions()
        repeat(days) { dayIndex ->
            val at = now - dayIndex * dayMs
            repeat(highPerDay) { dao.insert(prediction(at - it * 1000L, classIndex = 1)) }
            dao.insert(prediction(at - 60_000L, classIndex = 0))
        }
    }

    @Test
    fun launchesWithNoStoredReadingsAndSaysSo() {
        // Deliberately no assertion on `scenario.state`. It reports RESUMED only when the device
        // display is actually on, so it describes the test bench rather than the code and fails on
        // a locked phone while the Activity inflates and populates perfectly well.
        ActivityScenario.launch(TrendsActivity::class.java).use { scenario ->
            scenario.awaitVisible(R.id.tvTrendsEmpty)
            scenario.onActivity { activity ->
                val empty = activity.findViewById<TextView>(R.id.tvTrendsEmpty)
                assertTrue(
                    "the empty state should explain itself, was \"${empty.text}\"",
                    empty.text.contains("No readings stored yet"),
                )
                assertEquals(
                    "charts must be hidden with nothing to draw",
                    View.GONE,
                    activity.findViewById<View>(R.id.chartGroup).visibility,
                )
            }
        }
    }

    /** One day is data but not a trend, and the screen distinguishes the two. */
    @Test
    fun oneDayOfReadingsIsReportedAsTooSparse() {
        seed(days = 1, highPerDay = 4)

        ActivityScenario.launch(TrendsActivity::class.java).use { scenario ->
            scenario.awaitVisible(R.id.tvTrendsEmpty)
            scenario.onActivity { activity ->
                val empty = activity.findViewById<TextView>(R.id.tvTrendsEmpty)
                assertTrue(
                    "expected the one-day message, was \"${empty.text}\"",
                    empty.text.contains("Only one day"),
                )
            }
        }
    }

    /**
     * The real case: several days of readings, all three charts drawn.
     *
     * Asserts the datasets actually carry points rather than merely that the views exist — an empty
     * chart renders perfectly happily and would pass a visibility check alone.
     */
    @Test
    fun severalDaysOfReadingsDrawsEveryChart() {
        seed(days = 4, highPerDay = 5)

        ActivityScenario.launch(TrendsActivity::class.java).use { scenario ->
            scenario.awaitVisible(R.id.chartGroup)
            scenario.onActivity { activity ->
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.tvTrendsEmpty).visibility,
                )

                val stress = activity.findViewById<com.github.mikephil.charting.charts.BarChart>(
                    R.id.chartStress
                )
                assertEquals("one bar per day with readings", 4, stress.data.entryCount)

                val vitals = activity.findViewById<com.github.mikephil.charting.charts.LineChart>(
                    R.id.chartVitals
                )
                assertEquals("heart rate and sleep, on two axes", 2, vitals.data.dataSetCount)
                assertEquals(4, vitals.data.getDataSetByIndex(0).entryCount)

                val steps = activity.findViewById<com.github.mikephil.charting.charts.LineChart>(
                    R.id.chartActivity
                )
                assertEquals(4, steps.data.entryCount)

                val week = activity.findViewById<TextView>(R.id.tvWeekSummary)
                assertTrue(
                    "the headline should say how many days it speaks for, was \"${week.text}\"",
                    week.text.contains("4 days with readings"),
                )
            }
        }
    }

    /**
     * The screen loads on a coroutine started in onResume, so the views are not populated by the
     * time `launch` returns. Polls rather than sleeping a fixed amount, so a slow device does not
     * flake and a fast one does not wait.
     */
    private fun <A : androidx.activity.ComponentActivity> ActivityScenario<A>.awaitVisible(viewId: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            var visible = false
            onActivity { visible = it.findViewById<View>(viewId).visibility == View.VISIBLE }
            if (visible) return
            Thread.sleep(50)
        }
        throw AssertionError("view $viewId never became visible")
    }
}
