package com.example.stressguard.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Local history: predictions, latency samples and alerts.
 *
 * This is the durable store for the real-time path. Everything here is written without a
 * network, which is the point — the plan's central claim is that detection and alerting keep
 * working offline, so the record of them has to as well. Supabase syncs *from* this, later.
 */
@Database(
    entities = [
        StressPredictionEntity::class,
        LatencyMetricEntity::class,
        AlertEventEntity::class,
        DailyStepTotalEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class StressGuardDatabase : RoomDatabase() {

    abstract fun stressPredictions(): StressPredictionDao
    abstract fun latencyMetrics(): LatencyMetricDao
    abstract fun alertEvents(): AlertEventDao
    abstract fun dailyStepTotals(): DailyStepTotalDao

    companion object {
        private const val TAG = "STRESS_DB"
        private const val NAME = "stressguard.db"

        @Volatile
        private var instance: StressGuardDatabase? = null

        fun get(context: Context): StressGuardDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): StressGuardDatabase =
            Room.databaseBuilder(context, StressGuardDatabase::class.java, NAME)
                // No migrations yet. Destructive fallback is the right trade while the schema is
                // still moving: a developer reinstalling should not hit a crash, and no production
                // data exists to lose. Add real migrations before anyone relies on their history
                // surviving an update.
                //
                // Version 2 added daily_step_totals, so an existing install is wiped on upgrade.
                // Acceptable here, and unavoidable without a migration.
                .fallbackToDestructiveMigration()
                .build()

        /** Test hook so an in-memory database can be substituted. */
        fun overrideForTest(database: StressGuardDatabase?) {
            instance = database
        }
    }
}

/**
 * Retention, per plan §10: drop old rows so the database does not grow without bound.
 *
 * Only rows already synced are removed, so a long spell offline cannot silently discard
 * readings that never reached the backend.
 */
suspend fun StressGuardDatabase.purgeOlderThan(cutoffEpochMs: Long) {
    val predictions = stressPredictions().deleteSyncedOlderThan(cutoffEpochMs)
    val latency = latencyMetrics().deleteSyncedOlderThan(cutoffEpochMs)
    val alerts = alertEvents().deleteSyncedOlderThan(cutoffEpochMs)

    if (predictions + latency + alerts > 0) {
        Log.i(
            "STRESS_DB",
            "purged $predictions predictions, $latency latency samples, $alerts alerts"
        )
    }
}

const val RETENTION_DAYS = 30L
