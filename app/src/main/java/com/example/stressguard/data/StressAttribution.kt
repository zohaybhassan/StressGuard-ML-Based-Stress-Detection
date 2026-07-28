package com.example.stressguard.data

import com.example.stressguard.StressProfile
import com.example.stressguard.StressVitals
import kotlin.math.abs

/** A live input the wearer can actually influence, and which changes day to day. */
enum class LiveFeature { HEART_RATE, DAILY_STEPS, SLEEP }

/**
 * One input's contribution to the current prediction.
 *
 * [impact] is the drop in high-stress probability when this input alone is replaced by its
 * training median. Positive means the input pushed the prediction *towards* stress; negative means
 * it was pulling the other way.
 */
data class StressDriver(
    val feature: LiveFeature,
    val impact: Float,
    val observed: Float,
    val typical: Float,
) {
    /** True when the input sits on the side of typical that the model associates with stress. */
    val raisesStress: Boolean get() = impact > 0f

    /** How far from typical, as a plain word rather than a statistic. */
    val deviation: String
        get() {
            val ratio = if (typical == 0f) 0f else abs(observed - typical) / typical
            val size = when {
                ratio < 0.05f -> "about"
                ratio < 0.20f -> "slightly"
                ratio < 0.45f -> "well"
                else -> "far"
            }
            return when {
                size == "about" -> "about typical"
                observed > typical -> "$size above typical"
                else -> "$size below typical"
            }
        }
}

/**
 * Why the model produced the prediction it did.
 *
 * [profileImpact] is separated from the live drivers deliberately. The static profile — age,
 * gender, occupation, BMI — carries 1.65x the influence of the vitals in this model (see
 * docs/model-limitations.md), and it cannot be acted on. Reporting "your heart rate is why you are
 * stressed" while the profile did most of the work would be a comfortable answer rather than a
 * true one, so the share is measured and stated.
 */
data class StressExplanation(
    val label: String,
    val highStressProbability: Float,
    /** Live inputs, largest contribution first. */
    val drivers: List<StressDriver>,
    val profileImpact: Float,
    /** The inputs sat outside the model's training ranges, so all of this is less trustworthy. */
    val extrapolating: Boolean,
) {
    /** The live input that pushed hardest towards stress, or null if none did. */
    val leadingDriver: StressDriver? get() = drivers.firstOrNull()?.takeIf { it.raisesStress }

    /**
     * True when the unchangeable profile outweighs everything that happened today.
     *
     * Worth surfacing rather than hiding: it is the honest answer to "why am I stressed" in the
     * cases where today's readings are not really the reason.
     */
    val profileDominates: Boolean
        get() = profileImpact > (drivers.filter { it.raisesStress }.sumOf { it.impact.toDouble() }
            .toFloat())
}

/**
 * Works out which input drove a prediction, by asking the model.
 *
 * The method is one-at-a-time ablation: re-run the real ensemble with a single input replaced by
 * its training median, and measure how far the high-stress probability falls. The input whose
 * replacement costs the most is the one carrying the prediction.
 *
 * This uses the actual model rather than a plausible story told alongside it, which matters — a
 * heuristic like "heart rate above 90 means stress" could confidently contradict what the trees
 * actually did. It is also cheap: four extra inferences at roughly 45 ms each, and deliberately
 * never run on the real-time path.
 *
 * **Its limitation, stated plainly:** ablating one input at a time cannot see interactions. If
 * high heart rate only matters when sleep is short, that joint effect is invisible here and the
 * two will each look modest. A full Shapley attribution would capture it and is far more expensive;
 * for naming the leading driver to a person, this is enough, and it is honest about being a
 * single-factor answer.
 */
object StressAttribution {

    /**
     * Medians of `ml_engine/data/StressGuard_Iteration1_Raw_Units.csv`, the file the shipped model
     * was fit on. Medians rather than means because the reference should be a value the training
     * data actually contains.
     */
    const val TYPICAL_HEART_RATE = 75
    const val TYPICAL_DAILY_STEPS = 5840
    const val TYPICAL_SLEEP_HOURS = 7.9f

    /**
     * The profile the model treats as its baseline.
     *
     * Not arbitrary: the training pipeline one-hot encodes with `drop_first`, so Female,
     * Accountant and Normal BMI are exactly the categories represented by every flag being zero.
     * Age is the training median. Swapping to this measures what the person's own profile adds
     * over the model's own zero point.
     */
    val REFERENCE_PROFILE = StressProfile(
        age = 47,
        gender = "Female",
        occupation = "Accountant",
        bmi = "Normal",
    )

    /**
     * Supplies the high-stress probability for a hypothetical input.
     *
     * An interface rather than the inference service itself, so the arithmetic can be tested
     * against a known function without loading 13.8 MB of ONNX.
     */
    fun interface HighStressProbability {
        fun of(profile: StressProfile, vitals: StressVitals): Float
    }

    fun explain(
        probability: HighStressProbability,
        profile: StressProfile,
        vitals: StressVitals,
        label: String,
        extrapolating: Boolean,
    ): StressExplanation {
        val baseline = probability.of(profile, vitals)

        val drivers = listOf(
            StressDriver(
                feature = LiveFeature.HEART_RATE,
                impact = baseline - probability.of(
                    profile, vitals.copy(heartRate = TYPICAL_HEART_RATE)
                ),
                observed = vitals.heartRate.toFloat(),
                typical = TYPICAL_HEART_RATE.toFloat(),
            ),
            StressDriver(
                feature = LiveFeature.DAILY_STEPS,
                impact = baseline - probability.of(
                    profile, vitals.copy(dailySteps = TYPICAL_DAILY_STEPS)
                ),
                observed = vitals.dailySteps.toFloat(),
                typical = TYPICAL_DAILY_STEPS.toFloat(),
            ),
            StressDriver(
                feature = LiveFeature.SLEEP,
                impact = baseline - probability.of(
                    profile, vitals.copy(sleepHours = TYPICAL_SLEEP_HOURS)
                ),
                observed = vitals.sleepHours,
                typical = TYPICAL_SLEEP_HOURS,
            ),
        ).sortedByDescending { it.impact }

        return StressExplanation(
            label = label,
            highStressProbability = baseline,
            drivers = drivers,
            profileImpact = baseline - probability.of(REFERENCE_PROFILE, vitals),
            extrapolating = extrapolating,
        )
    }
}
