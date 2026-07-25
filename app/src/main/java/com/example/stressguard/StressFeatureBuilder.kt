package com.example.stressguard

import android.content.Context

/** Profile values the stress model consumes. Category names must match the training data. */
data class StressProfile(
    val age: Int,
    val gender: String,
    val occupation: String,
    val bmi: String,
)

/** Live sensor values in raw physical units: bpm, steps per day, hours slept. */
data class StressVitals(
    val heartRate: Int,
    val dailySteps: Int,
    val sleepHours: Float,
)

/**
 * Turns a profile and a sensor reading into the feature vector the exported ONNX models expect.
 *
 * Values are passed in raw physical units. The models are tree ensembles, which are
 * scale-invariant, and are trained on raw units directly -- see ml_engine/prepare_dataset.py.
 * Do not standardize here: an earlier version of the pipeline trained on z-scored columns while
 * this builder sent raw units, which pushed every sample past the largest split threshold in
 * every tree so all readings collapsed to one prediction.
 *
 * Feature *order* comes from the model manifest rather than being hardcoded, so re-exporting a
 * bundle with different columns fails loudly instead of silently misaligning the vector.
 */
object StressFeatureBuilder {

    private const val AGE = "Age"
    private const val HEART_RATE = "Heart Rate"
    private const val DAILY_STEPS = "Daily Steps"
    private const val SLEEP_DURATION = "Sleep Duration"
    private const val GENDER_MALE = "Gender_Male"

    /**
     * One-hot indicator categories. The training pipeline drops the first category of each
     * group, so "Female", "Accountant" and "Normal" have no column of their own -- they are
     * encoded by every flag in their group being zero.
     */
    private val OCCUPATIONS = listOf(
        "Artist", "Chef", "Doctor", "Engineer", "Lawyer", "Manager", "Nurse",
        "Sales Representative", "Salesperson", "Scientist", "Software Engineer",
        "Student", "Teacher", "Writer",
    )

    private val BMI_CATEGORIES = listOf("Obese", "Overweight", "Underweight")

    /** Every feature this app can supply, keyed by its name in the model manifest. */
    fun featureMap(profile: StressProfile, vitals: StressVitals): Map<String, Float> {
        val occupation = normalizeOccupation(profile.occupation)
        val features = LinkedHashMap<String, Float>()

        features[AGE] = profile.age.toFloat()
        features[HEART_RATE] = vitals.heartRate.toFloat()
        features[DAILY_STEPS] = vitals.dailySteps.toFloat()
        features[SLEEP_DURATION] = vitals.sleepHours
        features[GENDER_MALE] = flag(profile.gender, "Male")
        OCCUPATIONS.forEach { features["Occupation_$it"] = flag(occupation, it) }
        BMI_CATEGORIES.forEach { features["BMI Category_$it"] = flag(profile.bmi, it) }

        return features
    }

    /**
     * Builds the vector in the exact order [featureNames] declares.
     *
     * Throws when the model asks for something this app cannot produce, rather than
     * substituting a zero -- a missing feature silently read as zero is indistinguishable
     * from a legitimately absent one-hot flag, and would corrupt every prediction.
     */
    fun buildVector(
        profile: StressProfile,
        vitals: StressVitals,
        featureNames: List<String>,
    ): FloatArray {
        val features = featureMap(profile, vitals)
        val unsupported = featureNames.filterNot { features.containsKey(it) }
        require(unsupported.isEmpty()) {
            "Model expects features this app cannot supply: $unsupported. The exported bundle's " +
                "feature set changed; update StressFeatureBuilder to match."
        }
        return FloatArray(featureNames.size) { features.getValue(featureNames[it]) }
    }

    /** Builds from the saved profile, or returns null when the profile is incomplete. */
    fun build(
        context: Context,
        vitals: StressVitals,
        featureNames: List<String>,
    ): FloatArray? {
        val profile = SessionManager.readProfile(context) ?: return null
        return buildVector(profile, vitals, featureNames)
    }

    /** Feature names this builder knows how to produce, for tests and diagnostics. */
    fun knownFeatureNames(): Set<String> =
        featureMap(
            StressProfile(age = 0, gender = "", occupation = "", bmi = ""),
            StressVitals(heartRate = 0, dailySteps = 0, sleepHours = 0f),
        ).keys

    private fun flag(actual: String, expected: String): Float =
        if (actual.equals(expected, ignoreCase = true)) 1f else 0f

    private fun normalizeOccupation(value: String): String =
        when {
            value.equals("Sales Rep", ignoreCase = true) -> "Sales Representative"
            value.equals("Sales Person", ignoreCase = true) -> "Salesperson"
            else -> value
        }
}
