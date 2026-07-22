package com.example.stressguard

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.abs

data class StressPrediction(
    val label: String,
    val classIndex: Int,
    val confidence: Float,
    val probabilities: FloatArray,
)

class StressInferenceService(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions()

    private val models = listOf(
        ModelSpec("RandomForest", "stress_model/random_forest.onnx", "float_input", "output_probability"),
        ModelSpec("XGBoost", "stress_model/xgboost.onnx", "input", "probabilities"),
        ModelSpec("CatBoost", "stress_model/catboost.onnx", "features", "probabilities"),
    )

    private val sessions: List<Pair<ModelSpec, OrtSession>> = models.map { spec ->
        spec to environment.createSession(readAssetBytes(spec.assetPath), sessionOptions)
    }

    fun predict(features: FloatArray): StressPrediction {
        require(features.size == StressFeatureBuilder.FEATURE_COUNT) {
            "Expected ${StressFeatureBuilder.FEATURE_COUNT} features, received ${features.size}"
        }

        val averaged = FloatArray(CLASS_LABELS.size)

        sessions.forEach { (spec, session) ->
            val probabilities = runModel(spec, session, features)
            Log.d(
                "STRESS_MODEL",
                "${spec.name} probabilities=${probabilities.joinToString(prefix = "[", postfix = "]")}"
            )
            for (index in averaged.indices) {
                averaged[index] += probabilities[index] / sessions.size
            }
        }

        Log.d(
            "STRESS_MODEL",
            "ModelAverage probabilities=${averaged.joinToString(prefix = "[", postfix = "]")}"
        )

        val sensorCalibrated = calibrateWithSensorRisk(averaged, features)
        val classIndex = sensorCalibrated.indices.maxByOrNull { sensorCalibrated[it] } ?: 0
        return StressPrediction(
            label = CLASS_LABELS[classIndex],
            classIndex = classIndex,
            confidence = sensorCalibrated[classIndex],
            probabilities = sensorCalibrated,
        )
    }

    private fun calibrateWithSensorRisk(
        modelProbabilities: FloatArray,
        features: FloatArray,
    ): FloatArray {
        val heartRate = features[HEART_RATE_INDEX]
        val dailySteps = features[DAILY_STEPS_INDEX]
        val sleepHours = features[SLEEP_DURATION_INDEX]

        val heartRisk = ((heartRate - 70f) / 45f).coerceIn(0f, 1f)
        val lowSleepRisk = ((7f - sleepHours) / 3f).coerceIn(0f, 1f)
        val lowActivityRisk = ((6000f - dailySteps) / 5000f).coerceIn(0f, 1f)
        val sensorRisk = (
            heartRisk * 0.55f +
                lowSleepRisk * 0.30f +
                lowActivityRisk * 0.15f
            ).coerceIn(0f, 1f)

        // Tree models can be piecewise constant for a fixed profile, so this light
        // calibration keeps live wearable changes visible in the final app score.
        val sensorProbabilities = normalize(
            floatArrayOf(
                (1f - sensorRisk) * (1f - sensorRisk),
                (1f - abs(sensorRisk - 0.5f) * 2f).coerceIn(0f, 1f) * 1.25f,
                sensorRisk * sensorRisk,
            )
        )

        val calibrated = FloatArray(CLASS_LABELS.size) { index ->
            modelProbabilities[index] * MODEL_WEIGHT + sensorProbabilities[index] * SENSOR_WEIGHT
        }

        Log.d(
            "STRESS_MODEL",
            "SensorCalibration risk=$sensorRisk, sensorProbabilities=" +
                sensorProbabilities.joinToString(prefix = "[", postfix = "]") +
                ", calibrated=${calibrated.joinToString(prefix = "[", postfix = "]")}"
        )

        return normalize(calibrated)
    }

    private fun runModel(
        spec: ModelSpec,
        session: OrtSession,
        features: FloatArray,
    ): FloatArray {
        val inputShape = longArrayOf(1L, features.size.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), inputShape).use { inputTensor ->
            session.run(mapOf(spec.inputName to inputTensor)).use { result ->
                val output = result.get(spec.probabilityOutputName).orElse(null)
                    ?: throw IllegalStateException("Missing ONNX output ${spec.probabilityOutputName}")

                return extractProbabilities(output)
                    ?: throw IllegalStateException(
                        "Could not read probabilities from ${spec.assetPath}. " +
                            "Output type: ${describeValue(output)}"
                    )
            }
        }
    }

    private fun extractProbabilities(output: OnnxValue): FloatArray? =
        normalizeProbabilityVector(output.value)

    private fun normalizeProbabilityVector(value: Any?): FloatArray? {
        return when (value) {
            is OnnxValue -> normalizeProbabilityVector(value.value)
            is FloatArray -> normalize(value)
            is DoubleArray -> normalize(value.map { it.toFloat() }.toFloatArray())
            is IntArray -> normalize(value.map { it.toFloat() }.toFloatArray())
            is LongArray -> normalize(value.map { it.toFloat() }.toFloatArray())
            is Array<*> -> value.firstNotNullOfOrNull { normalizeProbabilityVector(it) }
            is List<*> -> value.firstNotNullOfOrNull { normalizeProbabilityVector(it) }
            is Map<*, *> -> mapToProbabilityVector(value)
            else -> {
                Log.w("STRESS_MODEL", "Unsupported ONNX probability value: ${describeAny(value)}")
                null
            }
        }
    }

    private fun mapToProbabilityVector(value: Map<*, *>): FloatArray {
        val probabilities = FloatArray(CLASS_LABELS.size)

        value.forEach { (key, rawValue) ->
            val classIndex = when (key) {
                is Number -> key.toInt()
                is String -> key.toIntOrNull()
                else -> null
            }

            if (classIndex != null && classIndex in probabilities.indices) {
                probabilities[classIndex] = when (rawValue) {
                    is Number -> rawValue.toFloat()
                    else -> 0f
                }
            }
        }

        return normalize(probabilities)
    }

    private fun normalize(values: FloatArray): FloatArray {
        val trimmed = if (values.size == CLASS_LABELS.size) values else values.copyOf(CLASS_LABELS.size)
        val sum = trimmed.sum()

        if (sum <= 0f) return trimmed
        return FloatArray(trimmed.size) { index -> trimmed[index] / sum }
    }

    private fun readAssetBytes(assetPath: String): ByteArray =
        appContext.assets.open(assetPath).use { it.readBytes() }

    private fun describeValue(value: OnnxValue): String =
        "${value.javaClass.name}, raw=${describeAny(value.value)}"

    private fun describeAny(value: Any?): String =
        when (value) {
            null -> "null"
            is Array<*> -> "Array(${value.size}) first=${describeAny(value.firstOrNull())}"
            is List<*> -> "List(${value.size}) first=${describeAny(value.firstOrNull())}"
            is Map<*, *> -> {
                val first = value.entries.firstOrNull()
                "Map(${value.size}) firstKey=${describeAny(first?.key)} firstValue=${describeAny(first?.value)}"
            }
            else -> value.javaClass.name
        }

    override fun close() {
        sessions.forEach { (_, session) -> session.close() }
        sessionOptions.close()
    }

    private data class ModelSpec(
        val name: String,
        val assetPath: String,
        val inputName: String,
        val probabilityOutputName: String,
    )

    companion object {
        val CLASS_LABELS = listOf("relaxed_low_stress", "normal", "stressed_high")
        private const val HEART_RATE_INDEX = 1
        private const val DAILY_STEPS_INDEX = 2
        private const val SLEEP_DURATION_INDEX = 3
        private const val MODEL_WEIGHT = 0.55f
        private const val SENSOR_WEIGHT = 0.45f
    }
}
