# StressGuard ML Engine

Trains the stress classifier and exports it to ONNX for on-device inference in the Android app.

All paths below are relative to this folder. Scripts resolve their own location, so they can be
run from anywhere.

```bash
pip install -r requirements.txt
```

## Pipeline

```text
data/sleep_health_dataset.csv          raw survey data, 1500 rows
  -> prepare_dataset.py                encode + oversample, keep raw units
  -> data/StressGuard_Iteration1_Raw_Units.csv     2360 rows, 22 features
  -> optimize_ml_engine.py             tune 5 model families, pick top 3 by CV
  -> artifacts_tuned_*_raw/            tuning_report.json with best_params
  -> export_mobile_model.py            refit + convert to ONNX
  -> mobile_export/*/                  3 .onnx files + manifest
  -> app/src/main/assets/stress_model/ copied by hand
```

### 1. Build the training set

```bash
python prepare_dataset.py
```

Reads the raw dataset, drops `Person ID` and `Physical Activity Level`, one-hot encodes
`Gender` / `Occupation` / `BMI Category` with `drop_first=True`, and oversamples the minority
classes to 236 each with `SMOTENC`.

Two things it deliberately does **not** do:

- **No standardization.** The four continuous features stay in raw physical units. The models
  are tree ensembles, which are scale-invariant, and the Android app can only send raw units.
  An earlier pipeline z-scored them, which meant every live reading landed beyond the outermost
  split in every tree and the ensemble returned a constant vector. See
  `diagnose_input_scale.py`.
- **No plain SMOTE.** SMOTE interpolates one-hot columns and the results get rounded back to
  0/1, producing rows with two occupations at once or both Obese and Overweight. In the
  original dataset that affected 504 of 2360 rows, concentrated in the rarest classes.
  `SMOTENC` takes the majority category among neighbours instead; the script asserts that no
  impossible combination survives.

It also writes `vital_scaling.json`, the mean/std recovered from the raw file. Nothing consumes
it any more; it documents the original preprocessing and reproduces the old z-scored CSV
exactly, which is what confirms `sleep_health_dataset.csv` is genuinely its source.

### 2. Tune

```bash
python optimize_ml_engine.py --label-mode binary-stress --binary-threshold 7 \
    --feature-mode base --output-dir artifacts_tuned_binary_raw

python optimize_ml_engine.py --label-mode three-level --three-level-scheme wide-normal \
    --feature-mode base --output-dir artifacts_tuned_widenormal_raw
```

`RandomizedSearchCV` over RandomForest, XGBoost, LightGBM, CatBoost and SVM, then a soft-voting
and a stacking ensemble over whichever three scored best in CV.

Note `--feature-mode` defaults to `engineered`; the shipped bundles use `base`. Label modes:

- `binary-stress` — class 1 when `Target_Stress_Level >= --binary-threshold` (default 7)
- `three-level` — `--three-level-scheme` one of `classic` (1-4 / 5-7 / 8-10),
  `wide-normal` (1-3 / 4-7 / 8-10), `early-stress` (1-4 / 5-6 / 7-10)
- `score-10` — the full 1-10 scale

### 3. Export to ONNX

```bash
python export_mobile_model.py --export-kind binary \
    --tuning-report artifacts_tuned_binary_raw/tuning_report.json \
    --dataset data/StressGuard_Iteration1_Raw_Units.csv \
    --output-dir mobile_export/binary_voting_top3_raw
```

`--ensemble-members` defaults to `RandomForest,XGBoost,CatBoost`, not the tuning report's CV top
three. The CV winners vary with the data and can include SVM or LightGBM, which this export path
cannot convert; the three tree models are what the Android bundle is built around and score
within a fraction of a point of the CV leaders.

`--dataset` overrides the `dataset_path` recorded in the tuning report, which is an absolute
path from whichever machine ran the tuning.

### 4. Verify before shipping

```bash
python evaluate_bundle.py --bundle mobile_export/binary_voting_top3_raw
```

Prints per-class precision/recall/F1 for the ensemble that was actually exported, and checks the
`.onnx` graphs against the sklearn estimator they came from. Do not ship a bundle where those
disagree.

```bash
python diagnose_input_scale.py --bundle mobile_export/binary_voting_top3_raw
```

Feeds two sharply different samples and confirms the output moves. Run it against a pre-fix
bundle for the contrast: raw inputs collapse to one prediction there and separate here.

### 5. Ship to Android

Copy the three `.onnx` files and `stressguard_mobile_manifest.json` into
`app/src/main/assets/stress_model/`, then regenerate the parity samples:

```bash
python make_parity_samples.py --bundle mobile_export/binary_voting_top3_raw
```

That writes `app/src/androidTest/assets/parity_samples.json`, which
`StressInferenceParityTest` checks the on-device results against. It must be regenerated
whenever the bundle changes, or the test compares against the wrong model.

## Bundles

| Task | Folder | Accuracy | F1 | High-stress recall |
|---|---|---|---|---|
| **Binary** (shipped) | `mobile_export/binary_voting_top3_raw/` | 0.8877 | 0.8878 | 0.8624 |
| Three-level | `mobile_export/three_level_voting_wide_normal_raw/` | 0.7797 | 0.7800 | 0.8014 |

The `binary_voting_top3/` and `three_level_voting_wide_normal/` folders without the `_raw`
suffix are the earlier z-scored exports. They are kept because they are the only way to
reproduce the before/after demonstration; nothing in the current pipeline uses them.

Each bundle contains:

| File | Purpose |
|---|---|
| `random_forest.onnx` | RF base learner (skl2onnx; probabilities come back as a ZipMap) |
| `xgboost.onnx` | XGBoost booster via onnxmltools |
| `catboost.onnx` | CatBoost native ONNX export |
| `stressguard_mobile_manifest.json` | feature order, class labels, ONNX I/O names, `input_units` |
| `voting_top3_sklearn.joblib` | the sklearn estimator, for verification (gitignored) |

## The manifest is the contract

The app reads everything it needs from `stressguard_mobile_manifest.json` rather than
duplicating it in Kotlin:

- `feature_names` — the exact column order the input vector must follow
- `n_classes`, `label.class_N` — how many outputs and what they mean
- `ensemble.members[].inputs` / `outputs` — each graph's input name and probability output
- `input_units` — `raw_physical`: unscaled years, bpm, step counts and hours

On device: build a `float32` `[1, n_features]` tensor in `feature_names` order, run all three
graphs, average the probability vectors element-wise, then argmax.

## Interactive prediction

```bash
python predict_stress_interactive.py
```

Prompts for a profile and vitals and prints the predicted class with probabilities. It reads
`input_units` from the manifest and enters the matching mode, because sending scaled values to a
raw-units model does not fail — it returns a confident, constant, wrong answer.

## Baseline

`train_ml_engine.py` is the earlier untuned pipeline (RandomForest, XGBoost, LightGBM, CatBoost,
LogisticRegression, and a RF+XGB stacking model with an MLP meta-learner). It is not part of the
current export path and is kept for the baseline-versus-tuned comparison.

## Known limitations

- Oversampling runs before the train/test split, so synthetic rows derived from test-fold
  neighbours reach the training set. This inflates the reported figures by an unquantified
  amount. Retained so numbers stay comparable to earlier write-ups; change it in
  `prepare_dataset.py` by splitting before calling `resample`.
- The dataset records *resting* heart rate, spanning 43-109 bpm. Live wearable heart rate
  exceeds that during ordinary activity, where the trees clamp to their outermost leaf.
- `Physical Activity Level` exists in the raw data but is not among the 22 features, because
  the original pipeline dropped it and the exported models were built without it.
