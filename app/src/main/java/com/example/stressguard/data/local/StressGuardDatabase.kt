package com.example.stressguard.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local history: predictions, latency samples, alerts and the health checklist.
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
        HealthChecklistEntity::class,
        StressFeedbackEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StressGuardDatabase : RoomDatabase() {

    abstract fun stressPredictions(): StressPredictionDao
    abstract fun latencyMetrics(): LatencyMetricDao
    abstract fun alertEvents(): AlertEventDao
    abstract fun dailyStepTotals(): DailyStepTotalDao
    abstract fun healthChecklists(): HealthChecklistDao
    abstract fun stressFeedback(): StressFeedbackDao

    companion object {
        private const val TAG = "STRESS_DB"
        private const val NAME = "stressguard.db"

        /**
         * Adds the activity level and the day-totals table.
         *
         * Existing rows get `activityLevel = 0`, matching the entity's default. That is a truthful
         * "not recorded" for predictions made before the app distinguished steps-since-midnight
         * from a full-day activity level, and it is what a fresh insert would have written.
         *
         * The DDL below is Room's own `createSql` from
         * `app/schemas/…StressGuardDatabase/3.json`, copied verbatim including the backticks.
         * Hand-written equivalents are where migrations go wrong: a stray `DEFAULT 0` or a
         * differently-spelled type makes `TableInfo.read()` disagree with the expected schema and
         * Room throws on the first launch after the update — on the user's device, not here.
         * Regenerate rather than retype when the schema changes.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQLite requires a default when adding a NOT NULL column to a populated table.
                // Room's expected schema records no default for this column, and its validation
                // only compares defaults it knows about, so the two agree.
                db.execSQL(
                    "ALTER TABLE `stress_predictions` ADD COLUMN `activityLevel` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_step_totals` " +
                        "(`date` TEXT NOT NULL, `steps` INTEGER NOT NULL, " +
                        "`updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`date`))"
                )
            }
        }

        /** Adds the health checklist that the rule-based risk score reads (plan §7, §16). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `health_checklists` " +
                        "(`id` INTEGER NOT NULL, `smoking` INTEGER NOT NULL, " +
                        "`heartCondition` INTEGER NOT NULL, `hypertension` INTEGER NOT NULL, " +
                        "`diabetes` INTEGER NOT NULL, `sleepDisorder` INTEGER NOT NULL, " +
                        "`anxietyHistory` INTEGER NOT NULL, `highCaffeineUse` INTEGER NOT NULL, " +
                        "`physicallyInactive` INTEGER NOT NULL, " +
                        "`updatedAtEpochMs` INTEGER NOT NULL, `synced` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** Adds user-confirmed labels and their immutable alert-time feature snapshots. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stress_feedback` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`alertEventId` INTEGER NOT NULL, `promptSource` TEXT NOT NULL, " +
                        "`alertFiredAtEpochMs` INTEGER NOT NULL, " +
                        "`predictionRecordedAtEpochMs` INTEGER NOT NULL, `predictedLabel` TEXT NOT NULL, " +
                        "`predictedClassIndex` INTEGER NOT NULL, `confidence` REAL NOT NULL, " +
                        "`probabilities` TEXT NOT NULL, `modelVersion` TEXT NOT NULL, " +
                        "`heartRate` INTEGER NOT NULL, `dailySteps` INTEGER NOT NULL, " +
                        "`activityLevel` INTEGER NOT NULL, `sleepHours` REAL NOT NULL, " +
                        "`outOfTrainingRange` INTEGER NOT NULL, `profileAge` INTEGER NOT NULL, " +
                        "`profileGender` TEXT NOT NULL, `profileOccupation` TEXT NOT NULL, " +
                        "`profileBmi` TEXT NOT NULL, `confirmedStressed` INTEGER, `severity` INTEGER, " +
                        "`respondedAtEpochMs` INTEGER, `synced` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_stress_feedback_alertFiredAtEpochMs` " +
                        "ON `stress_feedback` (`alertFiredAtEpochMs`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stress_feedback_synced` " +
                        "ON `stress_feedback` (`synced`)"
                )
            }
        }

        fun migrations(): Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        @Volatile
        private var instance: StressGuardDatabase? = null

        fun get(context: Context): StressGuardDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /**
         * Real migrations, not destructive fallback.
         *
         * The fallback was the right trade while history was disposable. It stopped being one once
         * the history became an input: the risk score in plan §7 counts high-stress *days* over the
         * last one or two weeks, so wiping the database on a version bump does not just lose a
         * display — it silently resets the recommendation to "not enough data" and takes a fortnight
         * to recover. The same rows are the report's evidence.
         *
         * Deliberately no `fallbackToDestructiveMigration()` alongside these. Leaving it in place
         * would mean a missing migration degrades to a silent wipe at exactly the moment the schema
         * changed, which is when a wipe is least likely to be noticed and most likely to be wrong.
         * A missing migration should fail loudly in development instead.
         */
        private fun build(context: Context): StressGuardDatabase =
            Room.databaseBuilder(context, StressGuardDatabase::class.java, NAME)
                .addMigrations(*migrations())
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
    val feedback = stressFeedback().deleteSyncedOlderThan(cutoffEpochMs)

    if (predictions + latency + alerts + feedback > 0) {
        Log.i(
            "STRESS_DB",
            "purged $predictions predictions, $latency latency samples, $alerts alerts, " +
                "$feedback feedback rows"
        )
    }
}

const val RETENTION_DAYS = 30L
