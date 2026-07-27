package com.example.stressguard.data

import com.example.stressguard.data.local.HealthChecklistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checkup rules from plan §7, and the five acceptance cases plan §16 names.
 *
 * Worth testing as thoroughly as the alert rule, for a sharper reason: this one tells a user
 * whether to go and see a doctor. Both failure directions cause harm — a missed recommendation
 * defeats the feature, and a spurious one sends a well person to a clinic on the word of an
 * undergraduate project.
 *
 * The wording is tested too. Plan §7 fixes the checkup sentence and forbids diagnostic language,
 * and a refactor that "improves" the copy would otherwise silently break a safety requirement.
 */
class RecommendationPolicyTest {

    // A day counts as high-stress at StressAlertPolicy.THRESHOLD readings, so the fixtures are
    // built in those terms rather than with a magic number.
    private val highReadings = StressAlertPolicy.THRESHOLD

    private fun day(
        index: Int,
        high: Boolean = false,
        heartRate: Int = 70,
        sleepHours: Float = 7.5f,
    ) = DailyStressSummary(
        date = "2026-07-%02d".format(index + 1),
        readings = 20,
        highStressReadings = if (high) highReadings else 0,
        averageHeartRate = heartRate,
        averageSleepHours = sleepHours,
        averageActivityLevel = 8000,
    )

    /** [highDays] high-stress days out of [total] days of data. */
    private fun days(
        total: Int,
        highDays: Int,
        heartRate: Int = 70,
        sleepHours: Float = 7.5f,
    ): List<DailyStressSummary> = (0 until total).map {
        day(it, high = it < highDays, heartRate = heartRate, sleepHours = sleepHours)
    }

    private fun checklist(
        smoking: Boolean = false,
        heartCondition: Boolean = false,
        hypertension: Boolean = false,
        diabetes: Boolean = false,
        anxietyHistory: Boolean = false,
        physicallyInactive: Boolean = false,
    ) = HealthChecklistEntity(
        smoking = smoking,
        heartCondition = heartCondition,
        hypertension = hypertension,
        diabetes = diabetes,
        anxietyHistory = anxietyHistory,
        physicallyInactive = physicallyInactive,
        updatedAtEpochMs = 0L,
    )

    private fun evaluate(
        last7: List<DailyStressSummary>,
        last14: List<DailyStressSummary> = last7,
        checklist: HealthChecklistEntity? = null,
        age: Int? = 30,
    ) = RecommendationPolicy.evaluate(
        RecommendationInput(last7Days = last7, last14Days = last14, checklist = checklist, age = age)
    )

    // ------------------------------------------------------------------
    // Plan §16's five acceptance cases
    // ------------------------------------------------------------------

    /** "Low-risk user with low stress receives no checkup recommendation." */
    @Test
    fun lowRiskLowStressGetsNoCheckup() {
        val result = evaluate(last7 = days(total = 7, highDays = 0), checklist = checklist())

        assertNotEquals(RecommendationAction.SUGGEST_CHECKUP, result.action)
        assertEquals(RecommendationAction.CALMING_GUIDANCE, result.action)
        assertEquals(RiskLevel.LOW, result.level)
        assertEquals(0, result.score)
    }

    /** "User with one high-stress day receives calming suggestion only." */
    @Test
    fun oneHighStressDayGetsCalmingGuidanceOnly() {
        val result = evaluate(last7 = days(total = 7, highDays = 1), checklist = checklist())

        assertEquals(RecommendationAction.CALMING_GUIDANCE, result.action)
        assertNotEquals(RecommendationPolicy.CHECKUP_MESSAGE, result.message)
        // One day is below the 3-day tier, so it scores nothing on its own.
        assertEquals(0, result.score)
    }

    /** "User with 5+ high-stress days and smoking receives checkup recommendation." */
    @Test
    fun fiveHighStressDaysWithSmokingSuggestsCheckup() {
        val result = evaluate(
            last7 = days(total = 7, highDays = 5),
            last14 = days(total = 14, highDays = 5),
            checklist = checklist(smoking = true),
        )

        assertEquals(RecommendationAction.SUGGEST_CHECKUP, result.action)
        assertEquals(RecommendationPolicy.CHECKUP_MESSAGE, result.message)
        // 25 for the stress tier + 15 for smoking.
        assertEquals(40, result.score)
        assertEquals(RiskLevel.MODERATE, result.level)
    }

    /** "User with heart condition and sustained high stress receives elevated/high recommendation." */
    @Test
    fun heartConditionWithSustainedStressIsElevatedOrHigher() {
        val result = evaluate(
            last7 = days(total = 7, highDays = 6),
            last14 = days(total = 14, highDays = 11),
            checklist = checklist(heartCondition = true, hypertension = true),
        )

        assertEquals(RecommendationAction.SUGGEST_CHECKUP, result.action)
        // 30 for 10+ days in 14, 25 heart condition, 20 hypertension.
        assertEquals(75, result.score)
        assertTrue(
            "expected ELEVATED or HIGH but was ${result.level}",
            result.level == RiskLevel.ELEVATED || result.level == RiskLevel.HIGH,
        )
    }

    /** "Confirm recommendation text explains the reason." */
    @Test
    fun recommendationExplainsItsReasoning() {
        val result = evaluate(
            last7 = days(total = 7, highDays = 5),
            last14 = days(total = 14, highDays = 5),
            checklist = checklist(smoking = true, diabetes = true),
        )

        assertTrue("the card has nothing to show its working with", result.hasFactors)
        val descriptions = result.factors.map { it.description }
        assertTrue(descriptions.any { it.contains("5 of the last 7 days") })
        assertTrue(descriptions.any { it.contains("Smoking") })
        assertTrue(descriptions.any { it.contains("Diabetes") })
        // Every factor carries the points it contributed, so the total is reconstructable.
        assertEquals(result.score, result.factors.sumOf { it.points })
    }

    // ------------------------------------------------------------------
    // Plan §7's rules
    // ------------------------------------------------------------------

    /** Rule 2: 10+ days in 14 warrants a checkup "even if the checklist risk factors are limited". */
    @Test
    fun tenHighStressDaysInFourteenSuggestsCheckupWithNoRiskFactors() {
        val result = evaluate(
            last7 = days(total = 7, highDays = 4),
            last14 = days(total = 14, highDays = 10),
            checklist = checklist(),
            age = 25,
        )

        assertEquals(RecommendationAction.SUGGEST_CHECKUP, result.action)
        assertEquals(30, result.score)
    }

    /** Five high-stress days but no major condition is not yet a checkup, per rule 1. */
    @Test
    fun fiveHighStressDaysWithoutMajorConditionDoesNotSuggestCheckup() {
        val result = evaluate(
            last7 = days(total = 7, highDays = 5),
            last14 = days(total = 14, highDays = 5),
            checklist = checklist(anxietyHistory = true),
        )

        assertEquals(RecommendationAction.MONITOR, result.action)
        assertNotEquals(RecommendationPolicy.CHECKUP_MESSAGE, result.message)
    }

    /**
     * The stress tiers are a ladder, not a sum.
     *
     * 11 high-stress days in 14 also means 5+ in 7, so adding all three tiers would score the same
     * fact three times and put a merely-stressed user at 70 before any health factor.
     */
    @Test
    fun stressTiersDoNotStack() {
        val result = evaluate(
            last7 = days(total = 7, highDays = 7),
            last14 = days(total = 14, highDays = 11),
            checklist = checklist(),
            age = 25,
        )

        assertEquals(30, result.score)
        assertEquals(1, result.factors.size)
    }

    /** Health factors alone must not send a well person to a doctor. */
    @Test
    fun healthFactorsWithoutSustainedStressDoNotSuggestCheckup() {
        val result = evaluate(
            last7 = days(total = 7, highDays = 0),
            last14 = days(total = 14, highDays = 0),
            checklist = checklist(smoking = true, heartCondition = true, hypertension = true),
            age = 60,
        )

        assertNotEquals(RecommendationAction.SUGGEST_CHECKUP, result.action)
        // The score still reflects the risk factors -- 15 + 25 + 20 + 10 for age.
        assertEquals(70, result.score)
        assertEquals(RiskLevel.ELEVATED, result.level)
    }

    @Test
    fun scoreIsCappedAtOneHundred() {
        val result = evaluate(
            last7 = days(total = 7, highDays = 7, heartRate = 95, sleepHours = 5.0f),
            last14 = days(total = 14, highDays = 14, heartRate = 95, sleepHours = 5.0f),
            checklist = checklist(
                smoking = true, heartCondition = true, hypertension = true,
                diabetes = true, anxietyHistory = true, physicallyInactive = true,
            ),
            age = 70,
        )

        assertEquals(100, result.score)
        assertEquals(RiskLevel.HIGH, result.level)
    }

    // ------------------------------------------------------------------
    // Individual factors
    // ------------------------------------------------------------------

    @Test
    fun lowSleepScoresOnlyBelowSixHours() {
        val below = evaluate(last7 = days(total = 7, highDays = 0, sleepHours = 5.4f))
        val above = evaluate(last7 = days(total = 7, highDays = 0, sleepHours = 6.4f))

        assertEquals(10, below.score)
        assertEquals(0, above.score)
    }

    /** 87 bpm is one standard deviation above the training set's mean resting rate. */
    @Test
    fun elevatedHeartRateNeedsMostDaysAboveTheThreshold() {
        val mostDays = evaluate(
            last7 = (0 until 7).map { day(it, heartRate = if (it < 5) 95 else 70) }
        )
        val fewDays = evaluate(
            last7 = (0 until 7).map { day(it, heartRate = if (it < 2) 95 else 70) }
        )

        assertEquals(15, mostDays.score)
        assertEquals(0, fewDays.score)
    }

    @Test
    fun ageScoresOnlyAboveFortyFive() {
        assertEquals(10, evaluate(last7 = days(7, 0), age = 46).score)
        assertEquals(0, evaluate(last7 = days(7, 0), age = 45).score)
        assertEquals(0, evaluate(last7 = days(7, 0), age = null).score)
    }

    /**
     * Sleep disorder and caffeine are collected but unscored: plan §7's table gives them no
     * weight, and inventing one would put an unsourced number into a medical-adjacent score.
     */
    @Test
    fun unscoredChecklistAnswersDoNotChangeTheScore() {
        val base = evaluate(last7 = days(7, 0), checklist = checklist())
        val withUnscored = evaluate(
            last7 = days(7, 0),
            checklist = checklist().copy(sleepDisorder = true, highCaffeineUse = true),
        )

        assertEquals(base.score, withUnscored.score)
    }

    // ------------------------------------------------------------------
    // Missing data
    // ------------------------------------------------------------------

    /** Too little history is its own state, not a low score. */
    @Test
    fun tooFewDaysReportsNotEnoughData() {
        val result = evaluate(last7 = days(total = 2, highDays = 2), last14 = days(total = 2, highDays = 2))

        assertEquals(RecommendationAction.NOT_ENOUGH_DATA, result.action)
        assertFalse(result.hasFactors)
        assertNotEquals(RecommendationPolicy.CHECKUP_MESSAGE, result.message)
    }

    @Test
    fun noHistoryAtAllReportsNotEnoughData() {
        val result = evaluate(last7 = emptyList(), last14 = emptyList())

        assertEquals(RecommendationAction.NOT_ENOUGH_DATA, result.action)
        assertEquals(0, result.score)
    }

    /** A user who skipped the checklist still gets a recommendation from their readings alone. */
    @Test
    fun missingChecklistStillProducesARecommendation() {
        val result = evaluate(
            last7 = days(total = 7, highDays = 4),
            last14 = days(total = 14, highDays = 10),
            checklist = null,
            age = 30,
        )

        assertEquals(RecommendationAction.SUGGEST_CHECKUP, result.action)
        assertEquals(30, result.score)
    }

    // ------------------------------------------------------------------
    // Wording, which plan §7 constrains and §25 repeats
    // ------------------------------------------------------------------

    @Test
    fun checkupWordingIsExactlyWhatThePlanRequires() {
        assertEquals(
            "Your recent stress pattern and health profile suggest that a routine medical " +
                "checkup may be helpful.",
            RecommendationPolicy.CHECKUP_MESSAGE,
        )
    }

    /**
     * No message may claim a diagnosis. Checked across every reachable action rather than on one
     * example, since the forbidden phrasing could be introduced into any of them.
     */
    @Test
    fun noMessageClaimsADiagnosis() {
        val forbidden = listOf(
            "you have a disease", "diagnos", "you are ill", "condition detected", "we detected",
        )

        val messages = listOf(
            evaluate(last7 = emptyList(), last14 = emptyList()),
            evaluate(last7 = days(7, 0)),
            evaluate(last7 = days(7, 3), last14 = days(14, 3)),
            evaluate(last7 = days(7, 5), last14 = days(14, 11), checklist = checklist(smoking = true)),
        ).map { it.message }

        for (message in messages) {
            for (phrase in forbidden) {
                assertFalse(
                    "\"$message\" contains forbidden phrasing \"$phrase\"",
                    message.lowercase().contains(phrase),
                )
            }
        }
    }
}
