package com.example.stressguard

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the feature vector the ONNX models are fed.
 *
 * Misalignment here is silent and total: the models accept any 22 floats and return confident
 * nonsense if the columns are in the wrong order or the wrong units. These tests read the
 * manifest that actually ships in assets, so they fail if a re-export changes the contract.
 */
class StressFeatureBuilderTest {

    private val profile = StressProfile(
        age = 34,
        gender = "Male",
        occupation = "Nurse",
        bmi = "Overweight",
    )

    private val vitals = StressVitals(heartRate = 88, dailySteps = 4200, sleepHours = 6.4f)

    private fun manifestFeatureNames(): List<String> {
        val candidates = listOf(
            File("src/main/assets/stress_model/stressguard_mobile_manifest.json"),
            File("app/src/main/assets/stress_model/stressguard_mobile_manifest.json"),
        )
        val manifest = candidates.firstOrNull { it.isFile }
            ?: error("model manifest not found; looked in ${candidates.map { it.absolutePath }}")

        val names = JSONObject(manifest.readText()).getJSONArray("feature_names")
        return List(names.length()) { names.getString(it) }
    }

    @Test
    fun builderSuppliesExactlyTheFeaturesTheShippedModelAsksFor() {
        val expected = manifestFeatureNames()
        val known = StressFeatureBuilder.knownFeatureNames()

        assertEquals(
            "features the manifest wants but the builder cannot produce",
            emptyList<String>(),
            expected.filterNot { it in known },
        )
        assertEquals(
            "features the builder produces that the manifest does not use",
            emptyList<String>(),
            known.filterNot { it in expected },
        )
    }

    @Test
    fun vectorFollowsManifestOrder() {
        val names = manifestFeatureNames()
        val vector = StressFeatureBuilder.buildVector(profile, vitals, names)
        val map = StressFeatureBuilder.featureMap(profile, vitals)

        assertEquals(names.size, vector.size)
        names.forEachIndexed { index, name ->
            assertEquals("position $index ($name)", map.getValue(name), vector[index], 0f)
        }
    }

    /**
     * Regression test for the defect that motivated this rewrite: the models were trained on
     * z-scored columns while the app sent raw units, so every reading collapsed to one
     * prediction. Raw units are now correct -- a normalized vector would be the bug.
     */
    @Test
    fun continuousFeaturesStayInRawPhysicalUnits() {
        val names = manifestFeatureNames()
        val vector = StressFeatureBuilder.buildVector(profile, vitals, names)

        assertEquals(34f, vector[names.indexOf("Age")], 0f)
        assertEquals(88f, vector[names.indexOf("Heart Rate")], 0f)
        assertEquals(4200f, vector[names.indexOf("Daily Steps")], 0f)
        assertEquals(6.4f, vector[names.indexOf("Sleep Duration")], 1e-6f)
    }

    @Test
    fun exactlyOneFlagIsSetPerCategoryGroup() {
        val map = StressFeatureBuilder.featureMap(profile, vitals)

        assertEquals(1f, map.getValue("Occupation_Nurse"), 0f)
        assertEquals(1f, map.getValue("BMI Category_Overweight"), 0f)
        assertEquals(1f, map.getValue("Gender_Male"), 0f)

        assertEquals(
            "one occupation flag set",
            1f,
            map.filterKeys { it.startsWith("Occupation_") }.values.sum(),
            0f,
        )
        assertEquals(
            "one BMI flag set",
            1f,
            map.filterKeys { it.startsWith("BMI Category_") }.values.sum(),
            0f,
        )
    }

    /** Female, Accountant and Normal are drop_first baselines: all flags zero, no column. */
    @Test
    fun baselineCategoriesEncodeAsAllZeros() {
        val map = StressFeatureBuilder.featureMap(
            profile.copy(gender = "Female", occupation = "Accountant", bmi = "Normal"),
            vitals,
        )

        assertEquals(0f, map.getValue("Gender_Male"), 0f)
        assertEquals(0f, map.filterKeys { it.startsWith("Occupation_") }.values.sum(), 0f)
        assertEquals(0f, map.filterKeys { it.startsWith("BMI Category_") }.values.sum(), 0f)
    }

    /** "Normal Weight" was the old dropdown label; it must still land on the Normal baseline. */
    @Test
    fun legacyBmiLabelStillEncodesAsNormalBaseline() {
        val map = StressFeatureBuilder.featureMap(profile.copy(bmi = "Normal Weight"), vitals)

        assertEquals(0f, map.filterKeys { it.startsWith("BMI Category_") }.values.sum(), 0f)
    }

    @Test
    fun legacyOccupationLabelsAreNormalized() {
        val salesRep = StressFeatureBuilder.featureMap(profile.copy(occupation = "Sales Rep"), vitals)
        assertEquals(1f, salesRep.getValue("Occupation_Sales Representative"), 0f)

        val salesPerson = StressFeatureBuilder.featureMap(profile.copy(occupation = "Sales Person"), vitals)
        assertEquals(1f, salesPerson.getValue("Occupation_Salesperson"), 0f)
    }

    @Test
    fun categoryMatchingIgnoresCase() {
        val map = StressFeatureBuilder.featureMap(
            profile.copy(gender = "male", occupation = "nurse", bmi = "overweight"),
            vitals,
        )

        assertEquals(1f, map.getValue("Gender_Male"), 0f)
        assertEquals(1f, map.getValue("Occupation_Nurse"), 0f)
        assertEquals(1f, map.getValue("BMI Category_Overweight"), 0f)
    }

    /** An unknown feature must fail loudly, not quietly become a zero. */
    @Test
    fun unknownFeatureIsRejected() {
        val names = manifestFeatureNames() + "Physical Activity Level"

        val error = runCatching {
            StressFeatureBuilder.buildVector(profile, vitals, names)
        }.exceptionOrNull()

        assertTrue(
            "expected IllegalArgumentException, got $error",
            error is IllegalArgumentException,
        )
        assertTrue(
            "message should name the offending feature: ${error?.message}",
            error?.message?.contains("Physical Activity Level") == true,
        )
    }

    /** Unselected categories must not silently shift the numeric columns. */
    @Test
    fun unrecognizedCategoryLeavesGroupAtBaselineWithoutShiftingOtherFeatures() {
        val names = manifestFeatureNames()
        val vector = StressFeatureBuilder.buildVector(
            profile.copy(occupation = "Astronaut"),
            vitals,
            names,
        )

        assertEquals(names.size, vector.size)
        assertEquals(88f, vector[names.indexOf("Heart Rate")], 0f)
        val occupationTotal = names.withIndex()
            .filter { it.value.startsWith("Occupation_") }
            .sumOf { vector[it.index].toDouble() }
        assertEquals(0.0, occupationTotal, 0.0)
    }

    @Test
    fun manifestDeclaresTheFeatureCountTheModelWasBuiltWith() {
        assertEquals(22, manifestFeatureNames().size)
        assertEquals(22, StressFeatureBuilder.knownFeatureNames().size)
    }
}
