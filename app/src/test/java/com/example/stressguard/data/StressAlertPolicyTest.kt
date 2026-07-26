package com.example.stressguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alert rule, exhaustively.
 *
 * This is the logic most worth testing in the app: it is entirely invisible until it misfires,
 * and both failure directions are bad. Too eager and the app cries wolf until notifications get
 * switched off; too reluctant and it never alerts, which is the feature not existing.
 *
 * Class 1 is the high-stress class throughout, matching the shipped binary bundle
 * (`not_stressed` / `stressed`).
 */
class StressAlertPolicyTest {

    private val high = 1
    private val low = 0
    private val now = 1_700_000_000_000L

    private fun evaluate(
        window: List<Int>,
        lastAlertEpochMs: Long? = null,
        nowEpochMs: Long = now,
    ) = StressAlertPolicy.evaluate(
        recentClassIndices = window,
        highStressClassIndex = high,
        nowEpochMs = nowEpochMs,
        lastAlertEpochMs = lastAlertEpochMs,
    )

    @Test
    fun threeHighOfFiveFires() {
        val decision = evaluate(listOf(low, high, low, high, high))

        assertTrue(decision is AlertDecision.Fire)
        decision as AlertDecision.Fire
        assertEquals(3, decision.highCount)
        assertEquals(5, decision.windowSize)
        assertTrue("the reason should be quotable in the UI", decision.reason.contains("3 of the last 5"))
    }

    /** The noise case the smoothing exists for: one spike from climbing stairs. */
    @Test
    fun aSingleHighReadingDoesNotFire() {
        assertEquals(AlertDecision.NotSustained, evaluate(listOf(low, low, high, low, low)))
    }

    @Test
    fun twoHighOfFiveDoesNotFire() {
        assertEquals(AlertDecision.NotSustained, evaluate(listOf(high, low, low, high, low)))
    }

    @Test
    fun fiveHighFires() {
        assertTrue(evaluate(List(5) { high }) is AlertDecision.Fire)
    }

    /** Only the most recent five count, so old stress does not keep re-triggering. */
    @Test
    fun onlyTheLastFiveReadingsAreConsidered() {
        val window = listOf(high, high, high, low, low, low, low, low)

        assertEquals(
            "the three high readings have aged out of the window",
            AlertDecision.NotSustained,
            evaluate(window),
        )
    }

    @Test
    fun threeHighWithOnlyThreeReadingsFires() {
        assertTrue(
            "3 of 3 satisfies 3 of the last 5 available",
            evaluate(listOf(high, high, high)) is AlertDecision.Fire,
        )
    }

    @Test
    fun fewerReadingsThanTheThresholdCannotFire() {
        assertEquals(AlertDecision.NotSustained, evaluate(emptyList()))
        assertEquals(AlertDecision.NotSustained, evaluate(listOf(high)))
        assertEquals(AlertDecision.NotSustained, evaluate(listOf(high, high)))
    }

    @Test
    fun sustainedStressInsideTheCooldownIsSuppressed() {
        val fiveMinutesAgo = now - 5 * 60 * 1000L
        val decision = evaluate(List(5) { high }, lastAlertEpochMs = fiveMinutesAgo)

        assertTrue(decision is AlertDecision.InCooldown)
        decision as AlertDecision.InCooldown
        assertEquals(
            "roughly five minutes left of a ten minute cooldown",
            5 * 60 * 1000L,
            decision.remainingMs,
        )
    }

    @Test
    fun cooldownExpiresExactlyOnTheBoundary() {
        val exactly = now - StressAlertPolicy.COOLDOWN_MS

        assertTrue(evaluate(List(5) { high }, lastAlertEpochMs = exactly) is AlertDecision.Fire)
    }

    @Test
    fun firesAgainAfterTheCooldown() {
        val longAgo = now - StressAlertPolicy.COOLDOWN_MS - 1

        assertTrue(evaluate(List(5) { high }, lastAlertEpochMs = longAgo) is AlertDecision.Fire)
    }

    /**
     * Not sustained takes precedence over cooldown. Reporting cooldown when the rule was never
     * met would tell the dashboard the wrong story about why nothing happened.
     */
    @Test
    fun notSustainedIsReportedEvenWhenAlsoInsideTheCooldown() {
        assertEquals(
            AlertDecision.NotSustained,
            evaluate(listOf(low, low, low, low, high), lastAlertEpochMs = now - 1000),
        )
    }

    /**
     * A wall clock that moved backwards must not mute alerts until it catches up, which with a
     * ten-minute cooldown and a timezone change could be hours.
     */
    @Test
    fun aClockThatWentBackwardsDoesNotSuppressIndefinitely() {
        val inTheFuture = now + 60 * 60 * 1000L

        assertTrue(evaluate(List(5) { high }, lastAlertEpochMs = inTheFuture) is AlertDecision.Fire)
    }

    @Test
    fun noPreviousAlertMeansNoCooldown() {
        assertTrue(evaluate(List(5) { high }, lastAlertEpochMs = null) is AlertDecision.Fire)
    }

    /**
     * With the three-level bundle the high class is index 2, and index 1 ("normal") must not
     * trigger. Guards against the high class being hardcoded.
     */
    @Test
    fun theHighClassIsWhicheverIndexIsPassedIn() {
        val threeLevelWindow = listOf(1, 2, 1, 2, 2)

        assertTrue(
            StressAlertPolicy.evaluate(threeLevelWindow, 2, now, null) is AlertDecision.Fire
        )
        assertEquals(
            "three 'normal' readings are not three high ones",
            AlertDecision.NotSustained,
            StressAlertPolicy.evaluate(listOf(1, 1, 1, 0, 0), 2, now, null),
        )
    }
}
