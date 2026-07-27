package com.example.stressguard

import android.content.Context
import org.json.JSONObject

/** One ONNX base learner in the soft-voting ensemble, as declared by the manifest. */
data class StressModelMember(
    val name: String,
    val assetPath: String,
    val inputName: String,
    val probabilityOutputName: String,
)

/**
 * Everything about the exported bundle that the app needs, read from
 * `stress_model/stressguard_mobile_manifest.json` rather than hardcoded.
 *
 * Reading it means a re-export that changes the feature order, the class count or a model's
 * ONNX input name is caught instead of silently producing wrong predictions.
 */
data class StressModelInfo(
    val selectedModel: String,
    val exportKind: String,
    val ensembleType: String,
    val featureNames: List<String>,
    val classLabels: List<String>,
    val testAccuracy: Double?,
    val testF1Weighted: Double?,
    val members: List<StressModelMember>,
) {
    val classCount: Int get() = classLabels.size

    /** Recorded against every prediction so stored history can be traced to a model build. */
    val version: String get() = "$selectedModel/$exportKind"

    companion object {

        /**
         * Reads the shipped manifest without touching ONNX Runtime.
         *
         * For callers that need the contract but not the model — the recommendation needs to know
         * which class index means high stress, and loading 13.8 MB of graphs to answer that would
         * be absurd. [StressInferenceService] holds the graphs; this holds only the JSON.
         */
        fun fromAssets(context: Context): StressModelInfo = parse(
            json = context.assets.open(StressInferenceService.MANIFEST_ASSET)
                .use { it.readBytes().decodeToString() },
            assetDir = StressInferenceService.ASSET_DIR,
        )

        fun parse(json: String, assetDir: String): StressModelInfo {
            val root = JSONObject(json)
            val label = root.optJSONObject("label")

            val featureNames = root.getJSONArray("feature_names").let { array ->
                List(array.length()) { array.getString(it) }
            }

            val declaredFeatures = root.optInt("n_features", featureNames.size)
            require(declaredFeatures == featureNames.size) {
                "manifest is inconsistent: n_features=$declaredFeatures but " +
                    "feature_names has ${featureNames.size} entries"
            }

            val classLabels = readClassLabels(root, label)
            val members = readMembers(root, assetDir)
            require(members.isNotEmpty()) { "manifest declares no ensemble members" }

            return StressModelInfo(
                selectedModel = root.optString("selected_model", "unknown"),
                exportKind = root.optString("export_kind", label?.optString("type") ?: "unknown"),
                ensembleType = root.optJSONObject("ensemble")
                    ?.optString("type")
                    .orEmpty()
                    .ifEmpty { "soft_vote_equal_weights" },
                featureNames = featureNames,
                classLabels = classLabels,
                testAccuracy = readMetric(root, "test_accuracy"),
                testF1Weighted = readMetric(root, "test_f1_weighted"),
                members = members,
            )
        }

        /**
         * Class names come from `label.class_0`, `class_1`, ... The binary bundle predates the
         * exporter that records `n_classes`, so the count is inferred from the keys present
         * when it is absent.
         */
        private fun readClassLabels(root: JSONObject, label: JSONObject?): List<String> {
            val declared = root.optInt("n_classes", label?.optInt("n_classes", 0) ?: 0)
            val labels = mutableListOf<String>()

            var index = 0
            while (true) {
                val name = label?.optString("class_$index").orEmpty()
                if (name.isEmpty()) break
                labels += name
                index++
            }

            if (labels.isEmpty()) {
                require(declared > 0) { "manifest declares neither class labels nor n_classes" }
                return List(declared) { "class_$it" }
            }
            require(declared == 0 || declared == labels.size) {
                "manifest is inconsistent: n_classes=$declared but ${labels.size} class labels"
            }
            return labels
        }

        private fun readMembers(root: JSONObject, assetDir: String): List<StressModelMember> {
            val members = root.getJSONObject("ensemble").getJSONArray("members")
            return List(members.length()) { index ->
                val member = members.getJSONObject(index)
                val file = member.getString("onnx_file")
                StressModelMember(
                    name = member.optString("tuned_name", file),
                    assetPath = "$assetDir/$file",
                    inputName = firstName(member.getJSONArray("inputs"))
                        ?: error("no input declared for $file"),
                    probabilityOutputName = probabilityOutput(member.getJSONArray("outputs"), file),
                )
            }
        }

        /** `inputs`/`outputs` are arrays of `[name, shape]` pairs. */
        private fun firstName(pairs: org.json.JSONArray): String? =
            if (pairs.length() == 0) null else pairs.getJSONArray(0).getString(0)

        /**
         * Each model exports both a label and a probability output. Pick the probability one:
         * RandomForest calls it `output_probability`, XGBoost and CatBoost `probabilities`.
         */
        private fun probabilityOutput(pairs: org.json.JSONArray, file: String): String {
            val names = List(pairs.length()) { pairs.getJSONArray(it).getString(0) }
            return names.firstOrNull { it.contains("probab", ignoreCase = true) }
                ?: error("no probability output declared for $file; outputs were $names")
        }

        /**
         * Prefer the metrics measured on the exported graphs themselves, then the refit, then
         * the figure carried over from tuning.
         */
        private fun readMetric(root: JSONObject, key: String): Double? {
            val blocks = listOf(
                "onnx_bundle_holdout_metrics_numpy_refit",
                "refit_holdout_metrics_dataframe_fit",
                "reported_voting_metrics",
            )
            for (name in blocks) {
                val value = root.optJSONObject(name)?.optDouble(key, Double.NaN) ?: Double.NaN
                if (!value.isNaN()) return value
            }
            return null
        }
    }
}
