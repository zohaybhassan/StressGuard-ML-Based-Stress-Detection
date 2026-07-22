# StressGuard ONNX on Android (Kotlin)

Two exported bundles (same file layout; different `stressguard_mobile_manifest.json` semantics):

| Task | Bundle folder | Tuning source | Reference holdout |
|------|----------------|---------------|-------------------|
| **Binary** stress | `mobile_export/binary_voting_top3/` | `artifacts_tuned_binary/tuning_report.json` | acc ~**0.894**, F1 ~**0.894** |
| **3-class** (wide-normal) | `mobile_export/three_level_voting_wide_normal/` | `artifacts_tuned_widenormal/tuning_report.json` | acc ~**0.814**, F1 ~**0.814** |

### Binary model

**`Voting_top3_tuned`** — class 1 = `Target_Stress_Level >= 7`, **base** features.

### 3-class model

**`Voting_top3_tuned`** — **wide-normal** bins on 1–10 score (see `optimize_ml_engine.map_to_three_level_stress`), **base** features.  
Manifest `label` maps classes **0 / 1 / 2** to relaxed / normal / stressed (see `stressguard_mobile_manifest.json`).

### Files (each bundle)

| File | Purpose |
|------|---------|
| `random_forest.onnx` | RF base learner |
| `xgboost.onnx` | XGBoost booster (ONNX) |
| `catboost.onnx` | CatBoost ONNX |
| `stressguard_mobile_manifest.json` | `feature_names`, `n_classes`, ONNX I/O names |
| `voting_top3_sklearn.joblib` | Python backup (`model`, `model_onnx_aligned`) |

On-device: **soft-average probability vectors** from the three models (length **2** for binary, **3** for 3-class), then **argmax** (binary: same as thresholding the averaged class-1 prob at 0.5).

## Gradle

In `app/build.gradle.kts` (or Groovy equivalent):

```kotlin
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")
}
```

Copy one bundle’s files into e.g. `app/src/main/assets/stressguard_binary/` or `app/src/main/assets/stressguard_3class/`.

## Kotlin outline

1. Parse `stressguard_mobile_manifest.json` and read **`n_classes`**, **`feature_names`**, and `ensemble.members[].inputs` / `outputs`.
2. Build **`float32` `[1, n_features]`** in column order = `feature_names`.
3. Load three `OrtSession`s (`random_forest.onnx`, `xgboost.onnx`, `catboost.onnx`).
4. For each model, get a **length-`n_classes`** probability vector (apply **softmax** if the ONNX returns logits).
5. **Average** the three vectors element-wise, then **`argmax`** → predicted class (`0 .. n_classes-1`).
6. Binary shortcut: after averaging, `prediction == 1` iff averaged `p[1] >= p[0]` (same as argmax for two classes).

## Tips

- Use **`Float`** (`float32`) for inputs; shape **`[1, n_features]`**.
- If you hit OOM, load sessions lazily or use ORT **NNAPI** execution provider where supported.
- For simpler deployment, consider a small **backend API** that runs the joblib model and returns JSON; ONNX is for fully on-device inference.

Regenerate ONNX after retuning:

```text
# Binary
python ml_engine/optimize_ml_engine.py --label-mode binary-stress --feature-mode base --output-dir ml_engine/artifacts_tuned_binary
python ml_engine/export_mobile_model.py --export-kind binary

# 3-class wide-normal
python ml_engine/optimize_ml_engine.py --label-mode three-level --three-level-scheme wide-normal --feature-mode base --output-dir ml_engine/artifacts_tuned_widenormal
python ml_engine/export_mobile_model.py --export-kind three_level_wide_normal
```
