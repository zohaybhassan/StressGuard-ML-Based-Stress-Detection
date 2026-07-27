package com.example.stressguard.data

import android.content.Context
import android.util.Log
import com.example.stressguard.SessionManager
import com.example.stressguard.StressModelInfo
import com.example.stressguard.data.local.StressGuardDatabase
import java.util.concurrent.TimeUnit

/**
 * Assembles what [RecommendationPolicy] needs and asks it for a verdict.
 *
 * Everything is read from Room and SharedPreferences, so a recommendation is produced with no
 * network — the same property the alert path has, and for the same reason: this is one of the
 * things the user sees, and it should not blank out because a train went into a tunnel.
 *
 * Nothing is cached. The evaluation is a group-by over at most a fortnight of rows and runs when
 * the dashboard opens, not per reading.
 */
object RecommendationRepository {

    private const val TAG = "STRESS_RECOMMEND"

    private val WINDOW_7_MS = TimeUnit.DAYS.toMillis(7)
    private val WINDOW_14_MS = TimeUnit.DAYS.toMillis(14)

    suspend fun current(
        context: Context,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Recommendation? = runCatching {
        val database = StressGuardDatabase.get(context)

        // The most severe class, from the manifest rather than hardcoded: the shipped bundle is
        // binary but the three-level one is swappable, and there the high class is index 2.
        val highStressClassIndex = StressModelInfo.fromAssets(context).classCount - 1

        // One query for the wider window; the 7-day view is a filter over the same rows rather
        // than a second read, so the two windows cannot disagree about where "now" is.
        val since14 = nowEpochMs - WINDOW_14_MS
        val since7 = nowEpochMs - WINDOW_7_MS
        val rows = database.stressPredictions().since(since14)

        val last14 = StressHistory.summarise(rows, highStressClassIndex)
        val last7 = StressHistory.summarise(
            rows.filter { it.recordedAtEpochMs >= since7 }, highStressClassIndex
        )

        RecommendationPolicy.evaluate(
            RecommendationInput(
                last7Days = last7,
                last14Days = last14,
                checklist = database.healthChecklists().current(),
                age = SessionManager.readProfile(context)?.age,
            )
        )
    }.onFailure {
        Log.w(TAG, "could not build a recommendation", it)
    }.getOrNull()

    /**
     * Recovers the checklist from Supabase if this device has none.
     *
     * Called from the dashboard rather than the splash router deliberately: it needs the network
     * and the router already waits on a profile pull, so doing it there would add a second timeout
     * to every cold start for something only one screen reads.
     */
    suspend fun ensureChecklist(context: Context) {
        runCatching { HealthChecklistRepository.ensureLocal(context) }
    }
}
