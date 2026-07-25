package com.example.stressguard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

data class StressPrediction(
    val label: String,
    val classIndex: Int,
    val confidence: Float,
    val probabilities: FloatArray,
    /** Which model build produced this, for stored history and for the report. */
    val modelVersion: String,
) {
    /** FloatArray gives this data class reference equality; compare by content instead. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StressPrediction) return false
        return label == other.label &&
            classIndex == other.classIndex &&
            confidence == other.confidence &&
            modelVersion == other.modelVersion &&
            probabilities.contentEquals(other.probabilities)
    }

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + classIndex
        result = 31 * result + confidence.hashCode()
        result = 31 * result + modelVersion.hashCode()
        result = 31 * result + probabilities.contentHashCode()
        return result
    }
}

/**
 * Runs the exported stress ensemble on device.
 *
 * The three ONNX base learners each produce a class-probability vector; those are averaged
 * element-wise (equal-weight soft vote, matching the sklearn VotingClassifier they were
 * exported from) and the argmax is the prediction.
 *
 * Nothing here adjusts the model's output. An earlier version blended in a hand-written
 * "sensor risk" score at 45% weight to make the gauge respond to heart rate, because the models
 * were being fed raw units while trained on z-scores and so returned a constant vector. The
 * models are now trained on raw units (ML_engine/prepare_dataset.py), which fixes the cause,
 * and the displayed score is the model's own output.
 *
 * Sessions are created lazily on first use. Loading all three graphs costs roughly 23 MB, so
 * [predict] must be called off the main thread.
 */
class StressInferenceService(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions()

    val modelInfo: StressModelInfo by lazy {
        StressModelInfo.parse(readAssetText(MANIFEST_ASSET), ASSET_DIR)
    }

    private val sessionsDelegate = lazy {
        modelInfo.members.map { member ->
            member to environment.createSession(readAssetBytes(member.assetPath), sessionOptions)
        }
    }

    private val sessions: List<Pair<StressModelMember, OrtSession>> by sessionsDelegate

    /** Builds the feature vector in the manifest's declared order, then predicts. */
    fun predict(profile: StressProfile, vitals: StressVitals): StressPrediction =
        predict(StressFeatureBuilder.buildVector(profile, vitals, modelInfo.featureNames))

    fun predict(features: FloatArray): StressPrediction {
        val expected = modelInfo.featureNames.size
        require(features.size == expected) {
            "Expected $expected features (per manifest), received ${features.size}"
        }

        val classCount = modelInfo.classCount
        val averaged = FloatArray(classCount)

        sessions.forEach { (member, session) ->
            val probabilities = runModel(member, session, features, classCount)
            Log.d(TAG, "${member.name} probabilities=${probabilities.joinToString(prefix = "[", postfix = "]")}")
            for (index in averaged.indices) {
                averaged[index] += probabilities[index] / sessions.size
            }
        }

        val normalized = normalize(averaged, classCount)
        val classIndex = normalized.indices.maxByOrNull { normalized[it] } ?: 0

        Log.d(
            TAG,
            "EnsembleAverage=${normalized.joinToString(prefix = "[", postfix = "]")} " +
                "class=${modelInfo.classLabels[classIndex]} model=${modelInfo.version}"
        )

        return StressPrediction(
            label = modelInfo.classLabels[classIndex],
            classIndex = classIndex,
            confidence = normalized[classIndex],
            probabilities = normalized,
            modelVersion = modelInfo.version,
        )
    }

    private fun runModel(
        member: StressModelMember,
        session: OrtSession,
        features: FloatArray,
        classCount: Int,
    ): FloatArray {
        val inputShape = longArrayOf(1L, features.size.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), inputShape).use { input ->
            session.run(mapOf(member.inputName to input)).use { result ->
                val output = result.get(member.probabilityOutputName).orElse(null)
                    ?: throw IllegalStateException(
                        "Missing ONNX output ${member.probabilityOutputName} in ${member.assetPath}"
                    )

                return probabilityVector(output.value, classCount)
                    ?: throw IllegalStateException(
                        "Could not read probabilities from ${member.assetPath}. " +
                            "Output type: ${describe(output.value)}"
                    )
            }
        }
    }

    /**
     * Unpacks a probability vector from the shapes the three exporters emit: a plain array,
     * a batch-of-one wrapping such an array, or the ZipMap output skl2onnx gives
     * RandomForest, which is a class-index-to-probability map.
     */
    private fun probabilityVector(value: Any?, classCount: Int): FloatArray? = when (value) {
        is OnnxValue -> probabilityVector(value.value, classCount)
        is FloatArray -> normalize(value, classCount)
        is DoubleArray -> normalize(FloatArray(value.size) { value[it].toFloat() }, classCount)
        is IntArray -> normalize(FloatArray(value.size) { value[it].toFloat() }, classCount)
        is LongArray -> normalize(FloatArray(value.size) { value[it].toFloat() }, classCount)
        is Array<*> -> value.firstNotNullOfOrNull { probabilityVector(it, classCount) }
        is List<*> -> value.firstNotNullOfOrNull { probabilityVector(it, classCount) }
        is Map<*, *> -> mapToProbabilityVector(value, classCount)
        else -> {
            Log.w(TAG, "Unsupported ONNX probability value: ${describe(value)}")
            null
        }
    }

    private fun mapToProbabilityVector(value: Map<*, *>, classCount: Int): FloatArray {
        val probabilities = FloatArray(classCount)
        var matched = 0

        value.forEach { (key, raw) ->
            val classIndex = when (key) {
                is Number -> key.toInt()
                is String -> key.toIntOrNull()
                else -> null
            }
            if (classIndex != null && classIndex in probabilities.indices && raw is Number) {
                probabilities[classIndex] = raw.toFloat()
                matched++
            }
        }

        require(matched == classCount) {
            "ZipMap output had $matched usable entries for $classCount classes: ${value.keys}"
        }
        return normalize(probabilities, classCount)
    }

    /**
     * Scales a vector to sum to 1. A length mismatch means the bundle and the manifest disagree,
     * which would silently reassign classes, so it throws rather than padding or truncating.
     */
    private fun normalize(values: FloatArray, classCount: Int): FloatArray {
        require(values.size == classCount) {
            "Model returned ${values.size} probabilities but the manifest declares $classCount classes"
        }
        val sum = values.sum()
        if (sum <= 0f) return values
        return FloatArray(values.size) { values[it] / sum }
    }

    private fun readAssetBytes(assetPath: String): ByteArray =
        appContext.assets.open(assetPath).use { it.readBytes() }

    private fun readAssetText(assetPath: String): String =
        appContext.assets.open(assetPath).use { it.readBytes().decodeToString() }

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Array<*> -> "Array(${value.size}) first=${describe(value.firstOrNull())}"
        is List<*> -> "List(${value.size}) first=${describe(value.firstOrNull())}"
        is Map<*, *> -> value.entries.firstOrNull().let {
            "Map(${value.size}) firstKey=${describe(it?.key)} firstValue=${describe(it?.value)}"
        }
        else -> value.javaClass.name
    }

    override fun close() {
        // Only if something was actually loaded -- touching `sessions` here would otherwise
        // build all three graphs just to tear them down again.
        if (sessionsDelegate.isInitialized()) {
            sessionsDelegate.value.forEach { (_, session) -> session.close() }
        }
        sessionOptions.close()
    }

    companion object {
        const val TAG = "STRESS_MODEL"
        private const val ASSET_DIR = "stress_model"
        private const val MANIFEST_ASSET = "$ASSET_DIR/stressguard_mobile_manifest.json"
    }
}
