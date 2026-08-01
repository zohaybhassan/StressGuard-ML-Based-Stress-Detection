package com.example.stressguard.data

import com.example.stressguard.data.local.DailyStepTotalDao
import com.example.stressguard.data.local.DailyStepTotalEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/**
 * The rule that decides what the model is told about activity.
 *
 * Worth pinning down precisely, because getting it wrong is silent and total: feeding the raw
 * count-since-midnight put every morning prediction outside the trained range, where the trees
 * clamp to their outermost leaf and stop responding to heart rate at all. A full night's run
 * produced 99 predictions, every one "stressed", every one an extrapolation.
 */
class StepHistoryTest {

    /** In-memory stand-in, so these stay pure unit tests with no Android or Room dependency. */
    private class FakeDao : DailyStepTotalDao {
        val rows = LinkedHashMap<String, DailyStepTotalEntity>()

        override suspend fun upsertMax(date: String, steps: Int, updatedAtEpochMs: Long) {
            val existing = rows[date]?.steps ?: Int.MIN_VALUE
            rows[date] = DailyStepTotalEntity(date, maxOf(existing, steps), updatedAtEpochMs)
        }

        override suspend fun totalFor(date: String): Int? = rows[date]?.steps

        override suspend fun mostRecentBefore(beforeDate: String): DailyStepTotalEntity? =
            rows.values.filter { it.date < beforeDate }.maxByOrNull { it.date }

        override suspend fun recent(limit: Int): List<DailyStepTotalEntity> =
            rows.values.sortedByDescending { it.date }.take(limit)

        override suspend fun deleteOlderThan(cutoffDate: String): Int {
            val doomed = rows.keys.filter { it < cutoffDate }
            doomed.forEach { rows.remove(it) }
            return doomed.size
        }

        override suspend fun count(): Int = rows.size
    }

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-07-26 09:00 UTC and the same clock time the day before. */
    private val morningToday = 1785056400000L
    private val morningYesterday = morningToday - 86_400_000L

    @Test
    fun `with no history the partial count is used unchanged`() = runTest {
        val history = StepHistory(FakeDao())

        // Honest rather than convenient: on day one the user's activity level is genuinely
        // unknown, and the prediction stays correctly flagged as an extrapolation.
        assertEquals(200, history.activityLevel(todaySteps = 200, nowEpochMs = morningToday))
    }

    @Test
    fun `a low morning count is replaced by yesterday's total`() = runTest {
        val dao = FakeDao()
        val history = StepHistory(dao)
        history.record(dailySteps = 8000, atEpochMs = morningYesterday)

        // The case that was broken: 200 steps at 9am is not this person's activity level.
        assertEquals(8000, history.activityLevel(todaySteps = 200, nowEpochMs = morningToday))
    }

    @Test
    fun `an unusually active day is reported rather than yesterday's total`() = runTest {
        val dao = FakeDao()
        val history = StepHistory(dao)
        history.record(dailySteps = 8000, atEpochMs = morningYesterday)

        assertEquals(11_500, history.activityLevel(todaySteps = 11_500, nowEpochMs = morningToday))
    }

    @Test
    fun `the highest count for a day wins, not the latest`() = runTest {
        val dao = FakeDao()
        val history = StepHistory(dao)

        history.record(dailySteps = 9000, atEpochMs = morningYesterday)
        // A reading arriving just after the watch's midnight reset must not wipe the day.
        history.record(dailySteps = 12, atEpochMs = morningYesterday + 1000)

        assertEquals(9000, dao.totalFor(StepHistory.dateKey(morningYesterday, utc)))
    }

    @Test
    fun `a gap in wear is tolerated by looking further back`() = runTest {
        val dao = FakeDao()
        val history = StepHistory(dao)
        // Four days ago, then nothing until today. The watch is not worn every day.
        history.record(dailySteps = 7400, atEpochMs = morningToday - 4 * 86_400_000L)

        assertEquals(7400, history.activityLevel(todaySteps = 300, nowEpochMs = morningToday))
    }

    @Test
    fun `today's own total never counts as history for today`() = runTest {
        val dao = FakeDao()
        val history = StepHistory(dao)
        history.record(dailySteps = 5000, atEpochMs = morningToday)

        // Reading back today's stored maximum would make the figure ratchet up and never fall,
        // so a single active morning would pin the input for the rest of the day.
        assertEquals(300, history.activityLevel(todaySteps = 300, nowEpochMs = morningToday))
    }

    @Test
    fun `a negative count is ignored rather than stored`() = runTest {
        val dao = FakeDao()
        val history = StepHistory(dao)
        history.record(dailySteps = -1, atEpochMs = morningYesterday)

        assertEquals(0, dao.count())
    }

    @Test
    fun `the day key uses local time so rollover happens at the user's midnight`() {
        val karachi = TimeZone.getTimeZone("Asia/Karachi")
        // 2026-07-26 20:00 UTC is already the 27th in Karachi (UTC+5).
        val evening = 1785096000000L

        assertEquals("2026-07-26", StepHistory.dateKey(evening, utc))
        assertEquals("2026-07-27", StepHistory.dateKey(evening, karachi))
    }
}
