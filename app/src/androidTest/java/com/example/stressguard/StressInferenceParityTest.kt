package com.example.stressguard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Python-vs-Android validation.
 *
 * `ml_engine/make_parity_samples.py` runs fixed samples through the same .onnx files this app
 * ships and records the resulting probabilities. This test reproduces both halves on-device:
 * the feature vector built from each profile, and the probabilities from running the ensemble.
 *
 * Agreement means the whole chain holds -- training, ONNX export, feature encoding, column
 * ordering and inference all line up. Divergence localises the fault: a feature-vector
 * mismatch is an encoding or ordering bug in the app, while matching vectors with differing
 * probabilities points at the ONNX runtime or the installed bundle.
 *
 * Regenerate the samples whenever the bundle is re-exported.
 */
@RunWith(AndroidJUnit4::class)
class StressInferenceParityTest {

    private lateinit var service: StressInferenceService
    private lateinit var expected: JSONObject

    @Before
    fun setUp() {
        // Model assets live in the app under test; the samples file ships with the test APK.
        service = StressInferenceService(InstrumentationRegistry.getInstrumentation().targetContext)
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("parity_samples.json")
            .use { it.readBytes().decodeToString() }
        expected = JSONObject(json)
    }

    @After
    fun tearDown() {
        service.close()
    }

    /**
     * Guards against comparing against samples generated from a different bundle than the one
     * installed, which would make every other assertion here meaningless.
     */
    @Test
    fun samplesWereGeneratedFromTheInstalledBundle() {
        assertEquals(
            "parity_samples.json was generated from a different model build; " +
                "re-run ml_engine/make_parity_samples.py",
            expected.getString("modelVersion"),
            service.modelInfo.version,
        )

        val names = expected.getJSONArray("featureNames")
        assertEquals(names.length(), service.modelInfo.featureNames.size)
        for (index in 0 until names.length()) {
            assertEquals(
                "feature $index",
                names.getString(index),
                service.modelInfo.featureNames[index],
            )
        }

        val labels = expected.getJSONArray("classLabels")
        assertEquals(labels.length(), service.modelInfo.classCount)
        for (index in 0 until labels.length()) {
            assertEquals(labels.getString(index), service.modelInfo.classLabels[index])
        }
    }

    /** The app must send raw physical units; a standardized vector would silently be wrong. */
    @Test
    fun featureVectorsMatchPython() {
        val samples = expected.getJSONArray("samples")
        val featureNames = service.modelInfo.featureNames

        for (index in 0 until samples.length()) {
            val sample = samples.getJSONObject(index)
            val name = sample.getString("name")
            val vector = StressFeatureBuilder.buildVector(
                profileOf(sample),
                vitalsOf(sample),
                featureNames,
            )

            val reference = sample.getJSONArray("features")
            assertEquals("$name: vector length", reference.length(), vector.size)
            for (position in 0 until reference.length()) {
                assertEquals(
                    "$name: feature $position (${featureNames[position]})",
                    reference.getDouble(position).toFloat(),
                    vector[position],
                    1e-4f,
                )
            }
        }
    }

    @Test
    fun probabilitiesMatchPython() {
        val samples = expected.getJSONArray("samples")
        val tolerance = expected.getDouble("tolerance").toFloat()
        var worst = 0f
        var worstAt = ""

        for (index in 0 until samples.length()) {
            val sample = samples.getJSONObject(index)
            val name = sample.getString("name")
            val prediction = service.predict(profileOf(sample), vitalsOf(sample))

            val reference = sample.getJSONArray("probabilities")
            assertEquals("$name: class count", reference.length(), prediction.probabilities.size)

            for (position in 0 until reference.length()) {
                val delta = kotlin.math.abs(
                    reference.getDouble(position).toFloat() - prediction.probabilities[position]
                )
                if (delta > worst) {
                    worst = delta
                    worstAt = "$name[$position]"
                }
                assertTrue(
                    "$name: probability $position differs by $delta " +
                        "(python=${reference.getDouble(position)}, " +
                        "android=${prediction.probabilities[position]}), tolerance $tolerance",
                    delta <= tolerance,
                )
            }

            assertEquals(
                "$name: predicted label",
                sample.getString("expectedLabel"),
                prediction.label,
            )
        }

        // Surfaced in the test output so the report can quote a real agreement figure.
        println("PARITY: ${samples.length()} samples, worst probability delta $worst at $worstAt")
    }

    @Test
    fun predictionsCarryTheModelVersion() {
        val sample = expected.getJSONArray("samples").getJSONObject(0)
        val prediction = service.predict(profileOf(sample), vitalsOf(sample))

        assertEquals(service.modelInfo.version, prediction.modelVersion)
        assertTrue("confidence should be the winning probability", prediction.confidence > 0f)
        assertEquals(
            prediction.probabilities[prediction.classIndex],
            prediction.confidence,
            1e-6f,
        )
    }

    /** Distinct vitals must move the output. A constant result is the defect this work fixed. */
    @Test
    fun differentVitalsProduceDifferentProbabilities() {
        val profile = StressProfile(age = 30, gender = "Male", occupation = "Nurse", bmi = "Normal")

        val calm = service.predict(profile, StressVitals(60, 12000, 9.0f))
        val strained = service.predict(profile, StressVitals(105, 1100, 5.2f))

        val delta = kotlin.math.abs(calm.probabilities[0] - strained.probabilities[0])
        assertTrue(
            "identical probabilities for very different vitals means the model is not " +
                "receiving them (delta=$delta)",
            delta > 0.01f,
        )
    }

    private fun profileOf(sample: JSONObject): StressProfile {
        val profile = sample.getJSONObject("profile")
        return StressProfile(
            age = profile.getInt("age"),
            gender = profile.getString("gender"),
            occupation = profile.getString("occupation"),
            bmi = profile.getString("bmi"),
        )
    }

    private fun vitalsOf(sample: JSONObject): StressVitals {
        val vitals = sample.getJSONObject("vitals")
        return StressVitals(
            heartRate = vitals.getInt("heartRate"),
            dailySteps = vitals.getInt("dailySteps"),
            sleepHours = vitals.getDouble("sleepHours").toFloat(),
        )
    }
}
