package com.example.stressguard.data

import com.example.stressguard.data.local.HealthChecklistEntity

/** Plan §7's bands. */
enum class RiskLevel { LOW, MODERATE, ELEVATED, HIGH }

/** What the app should actually say, which is not the same question as how high the score is. */
enum class RecommendationAction {
    /** Too little history to say anything. Not a low score — an absent one. */
    NOT_ENOUGH_DATA,

    /** Stress is not sustained. Plan §7: "show calming guidance only". */
    CALMING_GUIDANCE,

    /** Sustained enough to mention, not enough to send someone to a doctor. */
    MONITOR,

    /** Plan §7's two checkup rules are satisfied. */
    SUGGEST_CHECKUP,
}

/**
 * One scored factor, kept so the card can show its own working.
 *
 * Plan §7 chose a rule-based score over a model specifically so it could be explained; a number
 * with no breakdown would throw that away.
 */
data class RiskFactor(val description: String, val points: Int)

data class Recommendation(
    /** 0-100. See [RecommendationPolicy.score] for why the raw total is capped. */
    val score: Int,
    val level: RiskLevel,
    val action: RecommendationAction,
    val factors: List<RiskFactor>,
    /** User-facing text. Wording is constrained by plan §7; see [RecommendationPolicy]. */
    val message: String,
    val highStressDaysLast7: Int,
    val highStressDaysLast14: Int,
) {
    /** Days that carried enough readings to judge, for "based on N days of data". */
    val hasFactors: Boolean get() = factors.isNotEmpty()
}

/**
 * Everything the score reads. Assembled by the caller so the policy stays pure.
 *
 * The two windows are passed separately rather than derived from one list because the caller
 * already has to choose a cutoff to query on, and re-deriving it here would mean two places could
 * disagree about when "the last 7 days" starts.
 */
data class RecommendationInput(
    val last7Days: List<DailyStressSummary>,
    val last14Days: List<DailyStressSummary>,
    val checklist: HealthChecklistEntity?,
    val age: Int?,
)

/**
 * Plan §7's rule-based checkup recommendation.
 *
 * Rule-based rather than learned, and that is a deliberate safety choice rather than a shortcut: a
 * medical model would need clinically labelled data the project does not have, and this has to be
 * explainable to an examiner and to the user. Every point in the score traces to one sentence.
 *
 * **The app does not diagnose.** Plan §7 fixes the wording for exactly this reason, and the tests
 * assert that the forbidden phrasings never appear.
 *
 * Pure, like [StressAlertPolicy], so every branch is testable without a device, a database or a
 * clock.
 */
object RecommendationPolicy {

    // Plan §7's point table, verbatim.
    private const val POINTS_STRESS_3_TO_4_DAYS_OF_7 = 15
    private const val POINTS_STRESS_5_DAYS_OF_7 = 25
    private const val POINTS_STRESS_10_DAYS_OF_14 = 30
    private const val POINTS_SMOKING = 15
    private const val POINTS_HEART_CONDITION = 25
    private const val POINTS_HYPERTENSION = 20
    private const val POINTS_DIABETES = 15
    private const val POINTS_LOW_SLEEP = 10
    private const val POINTS_INACTIVE = 10
    private const val POINTS_ANXIETY = 10
    private const val POINTS_ELEVATED_HEART_RATE = 15
    private const val POINTS_AGE_OVER_45 = 10

    /** Plan §7: "Average sleep below 6 hours". */
    private const val LOW_SLEEP_HOURS = 6.0f

    /** Plan §7: "Age above 45". */
    private const val AGE_THRESHOLD = 45

    /**
     * What counts as an elevated average heart rate for a day.
     *
     * Plan §7 says "frequent elevated heart rate" without defining either word, so both are pinned
     * here rather than left to whoever reads the score. 87 bpm is one standard deviation above the
     * training set's mean resting rate (74.76 ± 12.23, `ml_engine/vital_scaling.json`) — derived
     * from the data the model was fit on rather than picked, so it can be justified in the report.
     */
    private const val ELEVATED_HEART_RATE_BPM = 87

    /** "Frequent": on at least half the days that have data. */
    private const val ELEVATED_HEART_RATE_DAY_FRACTION = 0.5

    /**
     * Days of data below which no recommendation is offered.
     *
     * A score computed from one afternoon is not a weekly pattern, and presenting it as one would
     * be the most misleading thing this file could do.
     */
    private const val MIN_DAYS_OF_DATA = 3

    /** The sentence plan §7 requires when suggesting a checkup. Not to be reworded. */
    const val CHECKUP_MESSAGE =
        "Your recent stress pattern and health profile suggest that a routine medical " +
            "checkup may be helpful."

    private const val CALMING_MESSAGE =
        "Your stress readings look settled. If you are feeling tense right now, a few slow " +
            "breaths or a short walk usually helps."

    private const val MONITOR_MESSAGE =
        "You have had some sustained stress recently. Keep an eye on it, and try to protect " +
            "your sleep and downtime this week."

    private const val NOT_ENOUGH_DATA_MESSAGE =
        "Not enough readings yet to describe a pattern. Wear your watch for a few days and " +
            "this will fill in."

    fun evaluate(input: RecommendationInput): Recommendation {
        val daysOfData = input.last14Days.size
        val highDays7 = StressHistory.highStressDayCount(input.last7Days)
        val highDays14 = StressHistory.highStressDayCount(input.last14Days)

        if (daysOfData < MIN_DAYS_OF_DATA) {
            return Recommendation(
                score = 0,
                level = RiskLevel.LOW,
                action = RecommendationAction.NOT_ENOUGH_DATA,
                factors = emptyList(),
                message = NOT_ENOUGH_DATA_MESSAGE,
                highStressDaysLast7 = highDays7,
                highStressDaysLast14 = highDays14,
            )
        }

        val factors = buildList {
            stressFactor(highDays7, highDays14)?.let(::add)
            addAll(checklistFactors(input.checklist))
            sleepFactor(input.last14Days)?.let(::add)
            heartRateFactor(input.last14Days)?.let(::add)
            ageFactor(input.age)?.let(::add)
        }

        val score = score(factors)
        val action = action(highDays7, highDays14, input.checklist)

        return Recommendation(
            score = score,
            level = level(score),
            action = action,
            factors = factors,
            message = when (action) {
                RecommendationAction.SUGGEST_CHECKUP -> CHECKUP_MESSAGE
                RecommendationAction.MONITOR -> MONITOR_MESSAGE
                RecommendationAction.CALMING_GUIDANCE -> CALMING_MESSAGE
                RecommendationAction.NOT_ENOUGH_DATA -> NOT_ENOUGH_DATA_MESSAGE
            },
            highStressDaysLast7 = highDays7,
            highStressDaysLast14 = highDays14,
        )
    }

    /**
     * The stress contribution, as the highest tier reached rather than the sum of all of them.
     *
     * Plan §7 lists the three thresholds as separate lines, but they are a ladder: 10 high-stress
     * days in 14 almost always also means 5 in 7, so adding them would score the same underlying
     * fact three times and put a merely-stressed user at 70 before any health factor was
     * considered.
     */
    private fun stressFactor(highDays7: Int, highDays14: Int): RiskFactor? = when {
        highDays14 >= 10 -> RiskFactor(
            "High stress on $highDays14 of the last 14 days", POINTS_STRESS_10_DAYS_OF_14
        )
        highDays7 >= 5 -> RiskFactor(
            "High stress on $highDays7 of the last 7 days", POINTS_STRESS_5_DAYS_OF_7
        )
        highDays7 >= 3 -> RiskFactor(
            "High stress on $highDays7 of the last 7 days", POINTS_STRESS_3_TO_4_DAYS_OF_7
        )
        else -> null
    }

    /**
     * The six checklist answers plan §7 assigns points to.
     *
     * `sleepDisorder` and `highCaffeineUse` are collected — plan §6 lists both as checklist fields
     * — but deliberately unscored, because §7's table gives them no weight and inventing one would
     * put an unsourced number into a medical-adjacent score. They are stored for the report and
     * for a later revision of the table.
     */
    private fun checklistFactors(checklist: HealthChecklistEntity?): List<RiskFactor> {
        if (checklist == null) return emptyList()
        return buildList {
            if (checklist.heartCondition) add(RiskFactor("Known heart condition", POINTS_HEART_CONDITION))
            if (checklist.hypertension) add(RiskFactor("High blood pressure", POINTS_HYPERTENSION))
            if (checklist.smoking) add(RiskFactor("Smoking", POINTS_SMOKING))
            if (checklist.diabetes) add(RiskFactor("Diabetes", POINTS_DIABETES))
            if (checklist.anxietyHistory) add(RiskFactor("History of anxiety", POINTS_ANXIETY))
            if (checklist.physicallyInactive) add(RiskFactor("Low physical activity", POINTS_INACTIVE))
        }
    }

    private fun sleepFactor(summaries: List<DailyStressSummary>): RiskFactor? {
        if (summaries.isEmpty()) return null
        val average = summaries.map { it.averageSleepHours }.average()
        if (average >= LOW_SLEEP_HOURS) return null
        return RiskFactor(
            "Average sleep of ${"%.1f".format(average)} hours", POINTS_LOW_SLEEP
        )
    }

    private fun heartRateFactor(summaries: List<DailyStressSummary>): RiskFactor? {
        if (summaries.isEmpty()) return null
        val elevated = summaries.count { it.averageHeartRate >= ELEVATED_HEART_RATE_BPM }
        if (elevated < summaries.size * ELEVATED_HEART_RATE_DAY_FRACTION) return null
        return RiskFactor(
            "Elevated average heart rate on $elevated of ${summaries.size} days",
            POINTS_ELEVATED_HEART_RATE,
        )
    }

    private fun ageFactor(age: Int?): RiskFactor? =
        if (age != null && age > AGE_THRESHOLD) RiskFactor("Age over $AGE_THRESHOLD", POINTS_AGE_OVER_45)
        else null

    /**
     * Capped at 100, because plan §7's bands stop there.
     *
     * The raw total can reach 160 for someone with every risk factor. Capping rather than
     * rescaling keeps each factor's stated point value truthful — the card shows "+25" next to
     * "known heart condition" and that has to be the number actually added.
     */
    private fun score(factors: List<RiskFactor>): Int =
        factors.sumOf { it.points }.coerceIn(0, 100)

    private fun level(score: Int): RiskLevel = when {
        score >= 75 -> RiskLevel.HIGH
        score >= 50 -> RiskLevel.ELEVATED
        score >= 25 -> RiskLevel.MODERATE
        else -> RiskLevel.LOW
    }

    /**
     * Plan §7's three recommendation rules.
     *
     * Note that this is driven by the stress pattern and the major conditions, **not** by the
     * score. A score can reach "Elevated" on health factors alone — age, smoking and inactivity
     * total 35 with no stress at all — and telling someone to see a doctor because of their age
     * and habits, when the app has observed no sustained stress, is not what the app is for.
     *
     * The middle case is not specified by §7: 2-4 high-stress days with no major condition falls
     * between "calming guidance only" and a checkup, so it is [RecommendationAction.MONITOR].
     */
    private fun action(
        highDays7: Int,
        highDays14: Int,
        checklist: HealthChecklistEntity?,
    ): RecommendationAction {
        val majorCondition = checklist != null && (
            checklist.smoking || checklist.heartCondition ||
                checklist.hypertension || checklist.diabetes
            )

        // Rule 2 first: 10+ days in 14 warrants a checkup on its own, "even if the checklist risk
        // factors are limited".
        if (highDays14 >= 10) return RecommendationAction.SUGGEST_CHECKUP
        if (highDays7 >= 5 && majorCondition) return RecommendationAction.SUGGEST_CHECKUP
        if (highDays7 <= 1) return RecommendationAction.CALMING_GUIDANCE
        return RecommendationAction.MONITOR
    }
}
