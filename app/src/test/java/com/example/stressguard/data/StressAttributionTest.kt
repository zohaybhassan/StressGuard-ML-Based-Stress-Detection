package com.example.stressguard.data

import com.example.stressguard.StressProfile
import com.example.stressguard.StressVitals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attribution by one-at-a-time ablation.
 *
 * Tested against a stand-in model whose behaviour is known exactly, so the arithmetic can be
 * checked without loading 13.8 MB of ONNX and without depending on what the real ensemble happens
 * to think this week. What is under test is the attribution, not the model.
 */
class StressAttributionTest {

    private val profile = StressProfile(age = 30, gender = "Male", occupation = "Engineer", bmi = "Normal")

    /** A model that only cares about heart rate: every point above typical adds 1% stress. */
    private val heartRateOnly = StressAttribution.HighStressProbability { _, vitals ->
        0.5f + (vitals.heartRate - StressAttribution.TYPICAL_HEART_RATE) * 0.01f
    }

    private fun explain(
        probability: StressAttribution.HighStressProbability,
        vitals: StressVitals,
        extrapolating: Boolean = false,
    ) = StressAttribution.explain(probability, profile, vitals, "stressed", extrapolating)

    @Test
    fun `the input the model actually uses is named as the driver`() {
        val result = explain(heartRateOnly, StressVitals(heartRate = 95, dailySteps = 3000, sleepHours = 6f))

        assertEquals(LiveFeature.HEART_RATE, result.leadingDriver?.feature)
        // 95 vs a typical 75 is 20 points, and this model charges 1% each.
        assertEquals(0.20f, result.leadingDriver!!.impact, 0.001f)
    }

    @Test
    fun `inputs the model ignores are reported as contributing nothing`() {
        val result = explain(heartRateOnly, StressVitals(heartRate = 95, dailySteps = 200, sleepHours = 3f))

        // Steps and sleep are wildly abnormal, but this model does not look at them. An
        // attribution that blamed them would be a plausible story rather than the truth.
        val steps = result.drivers.single { it.feature == LiveFeature.DAILY_STEPS }
        val sleep = result.drivers.single { it.feature == LiveFeature.SLEEP }
        assertEquals(0f, steps.impact, 0.001f)
        assertEquals(0f, sleep.impact, 0.001f)
    }

    @Test
    fun `an input pulling away from stress has a negative impact`() {
        val result = explain(heartRateOnly, StressVitals(heartRate = 60, dailySteps = 5840, sleepHours = 7.9f))

        val hr = result.drivers.single { it.feature == LiveFeature.HEART_RATE }
        assertTrue("a low heart rate should lower the estimate", hr.impact < 0f)
        assertFalse(hr.raisesStress)
        assertNull("nothing is pushing towards stress, so there is no driver", result.leadingDriver)
    }

    @Test
    fun `drivers come back strongest first`() {
        // Steps matter ten times more than heart rate to this model.
        val stepsHeavy = StressAttribution.HighStressProbability { _, vitals ->
            0.5f +
                (StressAttribution.TYPICAL_DAILY_STEPS - vitals.dailySteps) * 0.0001f +
                (vitals.heartRate - StressAttribution.TYPICAL_HEART_RATE) * 0.001f
        }

        val result = explain(stepsHeavy, StressVitals(heartRate = 95, dailySteps = 1000, sleepHours = 7.9f))

        assertEquals(LiveFeature.DAILY_STEPS, result.drivers[0].feature)
        assertEquals(LiveFeature.HEART_RATE, result.drivers[1].feature)
    }

    /**
     * The case the profile bucket exists for. Occupation carries 1.65x the influence of vitals in
     * the shipped model, so an assistant that always blamed today's readings would be reassuring
     * and wrong.
     */
    @Test
    fun `a prediction driven by the profile is reported as such`() {
        val profileHeavy = StressAttribution.HighStressProbability { candidate, vitals ->
            val fromProfile = if (candidate.occupation == "Engineer") 0.4f else 0f
            fromProfile + (vitals.heartRate - StressAttribution.TYPICAL_HEART_RATE) * 0.001f
        }

        val result = explain(profileHeavy, StressVitals(heartRate = 80, dailySteps = 5840, sleepHours = 7.9f))

        assertEquals(0.4f, result.profileImpact, 0.001f)
        assertTrue("the profile outweighs everything measured today", result.profileDominates)
    }

    @Test
    fun `a prediction driven by today's readings does not blame the profile`() {
        val result = explain(heartRateOnly, StressVitals(heartRate = 100, dailySteps = 5840, sleepHours = 7.9f))

        assertEquals("this model ignores the profile entirely", 0f, result.profileImpact, 0.001f)
        assertFalse(result.profileDominates)
    }

    @Test
    fun `deviation is described in words rather than statistics`() {
        val result = explain(heartRateOnly, StressVitals(heartRate = 110, dailySteps = 5900, sleepHours = 4f))

        assertEquals(
            "far above typical",
            result.drivers.single { it.feature == LiveFeature.HEART_RATE }.deviation,
        )
        assertEquals(
            "about typical",
            result.drivers.single { it.feature == LiveFeature.DAILY_STEPS }.deviation,
        )
        assertEquals(
            "far below typical",
            result.drivers.single { it.feature == LiveFeature.SLEEP }.deviation,
        )
    }

    @Test
    fun `the extrapolation flag is carried through`() {
        val result = explain(
            heartRateOnly,
            StressVitals(heartRate = 150, dailySteps = 100, sleepHours = 2f),
            extrapolating = true,
        )

        assertTrue(result.extrapolating)
    }

    @Test
    fun `the reference profile is the model's own zero point`() {
        // drop_first one-hot encoding means these three categories are represented by every flag
        // being zero, so swapping to them measures what a person's profile adds over nothing.
        assertEquals("Female", StressAttribution.REFERENCE_PROFILE.gender)
        assertEquals("Accountant", StressAttribution.REFERENCE_PROFILE.occupation)
        assertEquals("Normal", StressAttribution.REFERENCE_PROFILE.bmi)
    }

    /** The medians must stay inside the trained ranges, or the reference is itself an extrapolation. */
    @Test
    fun `typical values sit inside the model's training ranges`() {
        assertTrue(StressAttribution.TYPICAL_HEART_RATE in SensorReading.TRAINED_HEART_RATE)
        assertTrue(StressAttribution.TYPICAL_DAILY_STEPS in SensorReading.TRAINED_STEPS)
        assertTrue(StressAttribution.TYPICAL_SLEEP_HOURS in SensorReading.TRAINED_SLEEP_HOURS)
    }
}
