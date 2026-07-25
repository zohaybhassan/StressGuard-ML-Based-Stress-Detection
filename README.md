# StressGuard

StressGuard is an Android-based final year project for monitoring stress risk using a mobile app, a Wear OS companion app, wearable sensor data, user profile inputs, and an on-device machine learning pipeline.

The project currently focuses on a working prototype where the phone app signs in the user, collects profile information, receives heart rate and step data from the watch, reads sleep duration through Health Connect when available, and predicts a binary stress state using exported ONNX models.

## Current Status

The project has two Android modules:

- `app`: the mobile Android application.
- `wear`: the Wear OS companion application.

The mobile app is currently functional for:

- Google sign-in.
- Profile setup.
- Dashboard display.
- Watch data reception.
- Basic encrypted wearable data transfer.
- Health Connect sleep read path.
- ONNX model inference.
- Debug testing without a smartwatch.

The current stress classes are:

- `not_stressed`
- `stressed`

A three-level bundle (`relaxed_low_stress` / `normal` / `stressed_high`) is also exported and
kept in `ml_engine/mobile_export/`. The binary bundle ships because it is more accurate and
catches more high-stress readings; see Model Selection below. Switching between them is a
matter of copying a different bundle into the app's assets -- the app reads the class count
and labels from the bundle manifest rather than hardcoding them.

## Main App Flow

1. `MainActivity` starts first and routes the user based on saved session state.
2. If the user is not signed in, the app opens `LoginActivity`.
3. `LoginActivity` handles Google sign-in and stores basic Google account information.
4. If the user profile is incomplete, the app opens `ProfileSetupActivity`.
5. `ProfileSetupActivity` saves profile values such as name, age, gender, occupation, and BMI category.
6. After setup, the app opens `HomeDashboardActivity`.
7. `HomeDashboardActivity` displays heart rate, steps, sleep, connection state, and stress prediction.
8. `VitalReceiverService` receives encrypted watch data and broadcasts it to the dashboard.
9. `StressInferenceService` runs the ONNX ensemble and returns the stress prediction.

## Implemented Features

### Authentication

- Google sign-in is integrated using Google Play Services Auth.
- Google account display name, email, and photo URL can be stored in `SessionManager`.

Setup still required: `app/google-services.json` is **not** in the repository (it is gitignored,
and no local copy exists). Until a valid OAuth client is configured, sign-in fails with status
code 10, `DEVELOPER_ERROR`. `LoginActivity` already reports that case with a readable message.
- The profile setup screen can pre-fill supported fields from the Google account, mainly the user's display name.

### Profile Setup

The app stores the following user profile fields:

- Name
- Age
- Gender
- Occupation
- BMI category

These values are saved locally through `SessionManager` and are used by the stress feature builder.

### Wearable Data

The Wear OS module collects and sends wearable data to the phone.

Currently supported sensor values:

- Heart rate
- Step count

The phone receives these values through the Google Play Services Wearable APIs.

### Sleep Data

Sleep duration is read through Health Connect on the phone when permission and data are available.

Current limitation:

- Sleep may not appear if Health Connect has no sleep records from the watch or another health provider.
- For model testing, the dashboard includes simulated sleep values.

### BLE/Wearable Encryption

Basic encryption/decryption support exists in both modules:

- Mobile: `EncryptionUtil.kt`
- Wear: `EncryptionUtil.kt`

This protects the simple wearable payload before it is processed on the mobile side.

### Dashboard UI

The dashboard XML layout has been redesigned to look more like a modern health application while preserving existing view IDs so the Kotlin code continues to work.

The dashboard currently shows:

- Welcome text
- Watch connection status
- Heart rate
- Steps
- Sleep duration
- Stress percentage
- Stress status label
- Circular stress gauge
- Debug model test button

### ONNX Stress Prediction

The mobile app now includes a 3-model ONNX bundle:

- `random_forest.onnx`
- `xgboost.onnx`
- `catboost.onnx`
- `stressguard_mobile_manifest.json`

The models are stored in:

```text
app/src/main/assets/stress_model/
```

The Android app uses ONNX Runtime for inference:

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
```

The app runs the three models and averages their probability vectors as a soft-voting ensemble.

### Model Input Features

The model expects 22 features in this exact order:

```text
Age
Heart Rate
Daily Steps
Sleep Duration
Gender_Male
Occupation_Artist
Occupation_Chef
Occupation_Doctor
Occupation_Engineer
Occupation_Lawyer
Occupation_Manager
Occupation_Nurse
Occupation_Sales Representative
Occupation_Salesperson
Occupation_Scientist
Occupation_Software Engineer
Occupation_Student
Occupation_Teacher
Occupation_Writer
BMI Category_Obese
BMI Category_Overweight
BMI Category_Underweight
```

`StressFeatureBuilder.kt` is responsible for converting the saved profile and sensor values into this 22-value feature vector.

### Input Units (resolved defect)

Earlier builds observed that the ONNX models returned identical probabilities for different
sensor samples whenever the profile stayed the same. The cause was a units mismatch, not a
property of tree models:

- The training CSV had been standardized, so `Age`, `Heart Rate`, `Daily Steps` and
  `Sleep Duration` were z-scores roughly in the range -3 to +3.
- `StressFeatureBuilder` sent raw physical units: bpm, step counts, hours.

Every live reading therefore fell far beyond the largest split threshold in every tree, so all
samples reached the same terminal leaf and the ensemble returned a constant vector. The
workaround at the time blended a hand-written "sensor risk" score into the output at 45%
weight. Because the model's contribution was constant, that meant all of the gauge's actual
movement came from the hand-written formula rather than from the model.

The fix was applied at the source. RandomForest, XGBoost and CatBoost are scale-invariant, so
standardization was never needed: the models are now trained on raw physical units
(`ml_engine/prepare_dataset.py`) and the calibration layer has been deleted.

Current prediction flow:

```text
profile + vitals (raw units) -> 22-feature vector in manifest order
  -> three ONNX graphs -> equal-weight soft vote -> argmax
```

`ml_engine/diagnose_input_scale.py` demonstrates the defect and the fix. Run it against the
old bundle and the current one: raw inputs collapse to one prediction on the old bundle and
separate correctly on the new one, from the same two samples.

Bundle manifests now record `"input_units": "raw_physical"` so the contract is explicit, and
`StressInferenceService` reads feature order, class labels and per-model ONNX input/output
names from the manifest instead of duplicating them in Kotlin.

## Debug Testing Without Watch

The dashboard includes a `Run Model Test` button for testing without the smartwatch.

Each tap cycles through three simulated sensor scenarios:

- Relaxed: lower heart rate, high steps, good sleep.
- Normal: moderate heart rate, moderate steps, medium sleep.
- High stress: high heart rate, low steps, poor sleep.

The simulated values are combined with the currently saved user profile.

Useful Logcat tag:

```text
STRESS_MODEL
```

Expected debug logs include:

```text
ProfileBasedDebugScenario=...
ProfileSnapshot age=..., gender=..., occupation=..., bmi=...
RandomForest_tuned probabilities=[...]
XGBoost_tuned probabilities=[...]
CatBoost_tuned probabilities=[...]
EnsembleAverage=[...] class=... model=Voting_top3_tuned/binary
Prediction=..., confidence=..., probabilities=[...]
```

Debug scenario values are kept inside the training ranges (heart rate 43-109, steps
1000-16036, sleep 5.1-10.0). Beyond those the trees clamp to their outermost leaf, so an
out-of-range demo value produces the same output as the range edge.

## Model Selection

Both label modes were trained on the same raw-units dataset and evaluated on the same 472-sample
holdout. The binary bundle ships:

| | Binary (shipped) | Three-level |
|---|---|---|
| Accuracy | **0.8877** | 0.7797 |
| Weighted F1 | **0.8878** | 0.7800 |
| High-stress recall | **0.8624** | 0.8014 |
| High-stress precision | **0.8579** | 0.8188 |

Recall on the high-stress class is weighted most heavily, because that is the class that would
trigger an alert and a missed reading is the failure a user actually notices. Three-level costs
about 11 points of accuracy and 6 points of that recall.

Reproduce with:

```bash
python ml_engine/evaluate_bundle.py --bundle mobile_export/binary_voting_top3_raw
python ml_engine/evaluate_bundle.py --bundle mobile_export/three_level_voting_wide_normal_raw
```

## Accuracy Notes

The shipped binary bundle reports:

```text
Test accuracy: 88.77%
Weighted F1:   88.78%
High-stress recall: 86.24%   (163 of 189 caught)
```

These figures replace the earlier 89.4% / 81.4% numbers, which are not comparable: they were
measured on a dataset where oversampling had corrupted about 21% of the rows (see ML Pipeline
Notes below), and on models that the app was feeding inputs in the wrong units.

The exported ONNX graphs were checked against the sklearn estimator they came from: maximum
probability difference 2.3e-07 across the holdout, with zero label disagreements.

This accuracy belongs to the model evaluation dataset used during training/export. It should not be presented as guaranteed real-world medical accuracy.

Recommended wording for the project report:

```text
The exported binary stress model achieved 88.8% accuracy and 86.2% recall on the high-stress
class on a held-out test set. Inference runs entirely on the Android device using a soft-voting
ensemble of three ONNX gradient-boosted and tree models, with no server round trip. The model
consumes raw physical sensor units directly; predictions are not post-processed.
```

Two limitations to state honestly alongside those numbers:

- The training data is a survey dataset of *resting* heart rate spanning 43-109 bpm. Live
  wearable heart rate exceeds 109 during ordinary activity, where the model extrapolates.
- Oversampling is applied before the train/test split, so synthetic rows derived from
  test-fold neighbours reach the training set. This inflates the reported figure by an
  unquantified amount. It was kept deliberately so the number stays comparable to earlier
  write-ups; `ml_engine/prepare_dataset.py` documents where to change it.

StressGuard should be treated as a stress-risk monitoring prototype, not a clinical diagnosis tool.

## ML Pipeline Notes

`ml_engine/prepare_dataset.py` rebuilds the training set from `data/sleep_health_dataset.csv`
(1500 rows) and replaces the original Colab notebook, which referenced an input file that was
never committed. It corrects two defects in the original preparation:

1. **Standardization.** Removed. The four continuous features stay in raw physical units, which
   the tree ensembles handle natively and which matches what the Android app can actually send.

2. **Oversampling of categoricals.** The notebook one-hot encoded before running SMOTE, so the
   indicator columns were interpolated and then rounded back to 0/1. That produced 462 rows with
   two occupations set at once, 81 rows both Obese and Overweight, and about 218 rows whose BMI
   flags were zeroed and silently relabelled Normal -- 504 of 2360 rows (21.4%) encoding people
   who cannot exist, concentrated in the rarest classes. Oversampling now uses `SMOTENC` on the
   intact categorical columns, which takes the majority category among neighbours. The script
   asserts that no impossible combination survives.

The recovered original preprocessing statistics are written to `ml_engine/vital_scaling.json`
for reference. Nothing in the current pipeline consumes them.

## Folder Structure

```text
StressGuard/
â”œâ”€â”€ app/
â”‚   â”œâ”€â”€ build.gradle.kts
â”‚   â”œâ”€â”€ google-services.json
â”‚   â””â”€â”€ src/
â”‚       â”œâ”€â”€ androidTest/
â”‚       â”‚   â”œâ”€â”€ assets/
â”‚       â”‚   â”‚   â””â”€â”€ parity_samples.json      # generated by ml_engine
â”‚       â”‚   â””â”€â”€ java/com/example/stressguard/
â”‚       â”‚       â””â”€â”€ StressInferenceParityTest.kt
â”‚       â”œâ”€â”€ main/
â”‚       â”‚   â”œâ”€â”€ AndroidManifest.xml
â”‚       â”‚   â”œâ”€â”€ assets/
â”‚       â”‚   â”‚   â””â”€â”€ stress_model/
â”‚       â”‚   â”‚       â”œâ”€â”€ catboost.onnx
â”‚       â”‚   â”‚       â”œâ”€â”€ random_forest.onnx
â”‚       â”‚   â”‚       â”œâ”€â”€ stressguard_mobile_manifest.json
â”‚       â”‚   â”‚       â””â”€â”€ xgboost.onnx
â”‚       â”‚   â”œâ”€â”€ java/com/example/stressguard/
â”‚       â”‚   â”‚   â”œâ”€â”€ EncryptionUtil.kt
â”‚       â”‚   â”‚   â”œâ”€â”€ HomeDashboardActivity.kt
â”‚       â”‚   â”‚   â”œâ”€â”€ LoginActivity.kt
â”‚       â”‚   â”‚   â”œâ”€â”€ MainActivity.kt
â”‚       â”‚   â”‚   â”œâ”€â”€ ProfileSetupActivity.kt
â”‚       â”‚   â”‚   â”œâ”€â”€ SessionManager.kt
â”‚       â”‚   â”‚   â”œâ”€â”€ StressFeatureBuilder.kt
â”‚       â”‚   â”‚   â”œâ”€â”€ StressInferenceService.kt
â”‚       â”‚   â”‚   â”œâ”€â”€ StressModelInfo.kt
â”‚       â”‚   â”‚   â””â”€â”€ VitalReceiverService.kt
â”‚       â”‚   â””â”€â”€ res/
â”‚       â”‚       â”œâ”€â”€ drawable/
â”‚       â”‚       â”‚   â”œâ”€â”€ bg_metric_heart.xml
â”‚       â”‚       â”‚   â”œâ”€â”€ bg_metric_sleep.xml
â”‚       â”‚       â”‚   â””â”€â”€ bg_metric_steps.xml
â”‚       â”‚       â”œâ”€â”€ layout/
â”‚       â”‚       â”‚   â”œâ”€â”€ activity_home_dashboard.xml
â”‚       â”‚       â”‚   â”œâ”€â”€ activity_login.xml
â”‚       â”‚       â”‚   â”œâ”€â”€ activity_main.xml
â”‚       â”‚       â”‚   â””â”€â”€ activity_profile_setup.xml
â”‚       â”‚       â”œâ”€â”€ menu/
â”‚       â”‚       â”‚   â””â”€â”€ bottom_nav_menu.xml
â”‚       â”‚       â”œâ”€â”€ values/
â”‚       â”‚       â”‚   â”œâ”€â”€ colors.xml
â”‚       â”‚       â”‚   â”œâ”€â”€ strings.xml
â”‚       â”‚       â”‚   â””â”€â”€ themes.xml
â”‚       â”‚       â”œâ”€â”€ values-night/
â”‚       â”‚       â”‚   â””â”€â”€ themes.xml
â”‚       â”‚       â””â”€â”€ xml/
â”‚       â”‚           â”œâ”€â”€ backup_rules.xml
â”‚       â”‚           â””â”€â”€ data_extraction_rules.xml
â”‚       â””â”€â”€ test/
â”‚           â””â”€â”€ java/com/example/stressguard/
â”‚               â””â”€â”€ StressFeatureBuilderTest.kt
â”œâ”€â”€ wear/
â”‚   â”œâ”€â”€ build.gradle.kts
â”‚   â”œâ”€â”€ lint.xml
â”‚   â””â”€â”€ src/
â”‚       â””â”€â”€ main/
â”‚           â”œâ”€â”€ AndroidManifest.xml
â”‚           â”œâ”€â”€ java/com/example/stressguard/presentation/
â”‚           â”‚   â”œâ”€â”€ EncryptionUtil.kt
â”‚           â”‚   â”œâ”€â”€ MainActivity.kt
â”‚           â”‚   â””â”€â”€ theme/
â”‚           â”‚       â””â”€â”€ Theme.kt
â”‚           â””â”€â”€ res/
â”‚               â”œâ”€â”€ drawable/
â”‚               â”œâ”€â”€ mipmap-anydpi/
â”‚               â”œâ”€â”€ values/
â”‚               â””â”€â”€ values-round/
â”œâ”€â”€ docs/
â”œâ”€â”€ gradle/
â”œâ”€â”€ build.gradle.kts
â”œâ”€â”€ gradle.properties
â”œâ”€â”€ gradlew
â”œâ”€â”€ gradlew.bat
â”œâ”€â”€ local.properties
â”œâ”€â”€ README.md
â””â”€â”€ settings.gradle.kts
```

## Key Files

- `app/src/main/java/com/example/stressguard/MainActivity.kt`: routes user to login, profile setup, or dashboard.
- `app/src/main/java/com/example/stressguard/LoginActivity.kt`: handles Google sign-in.
- `app/src/main/java/com/example/stressguard/ProfileSetupActivity.kt`: saves user profile details.
- `app/src/main/java/com/example/stressguard/SessionManager.kt`: stores session, Google account, and profile data.
- `app/src/main/java/com/example/stressguard/HomeDashboardActivity.kt`: dashboard UI logic, live data display, sleep read path, and debug testing.
- `app/src/main/java/com/example/stressguard/VitalReceiverService.kt`: receives wearable messages on the phone.
- `app/src/main/java/com/example/stressguard/StressFeatureBuilder.kt`: builds the 22-feature model input vector, ordered by the manifest.
- `app/src/main/java/com/example/stressguard/StressModelInfo.kt`: parses the bundle manifest (feature order, class labels, per-model ONNX input/output names).
- `app/src/main/java/com/example/stressguard/StressInferenceService.kt`: loads the ONNX graphs and runs the soft-voting ensemble.

ML engine scripts:

- `ml_engine/prepare_dataset.py`: builds the training set from the raw dataset (raw units, SMOTENC).
- `ml_engine/optimize_ml_engine.py`: hyperparameter tuning across five model families.
- `ml_engine/export_mobile_model.py`: exports the chosen ensemble to ONNX plus a manifest.
- `ml_engine/evaluate_bundle.py`: per-class metrics for an exported bundle, and ONNX-vs-sklearn agreement.
- `ml_engine/diagnose_input_scale.py`: demonstrates the input-units defect and verifies the fix.
- `ml_engine/make_parity_samples.py`: generates the fixed samples the Android parity test checks against.
- `wear/src/main/java/com/example/stressguard/presentation/MainActivity.kt`: Wear OS side for collecting/sending sensor data.
- `wear/src/main/java/com/example/stressguard/presentation/EncryptionUtil.kt`: wearable encryption helper.

## Current Dependencies

Mobile app:

- AndroidX Core/AppCompat/Activity/ConstraintLayout
- Material Components
- Google Play Services Wearable
- Google Play Services Auth
- Health Connect client
- Lifecycle Runtime KTX
- ONNX Runtime Android

Wear app:

- Wearable APIs
- Jetpack Compose for Wear UI
- AndroidX Health Services client
- Core SplashScreen

## Known Limitations

- Sleep data depends on Health Connect availability and whether a provider has written sleep
  records. When none exist the app now falls back to an assumed 7.5 hours, labelled as such in
  the UI, so inference still runs instead of stalling.
- The watch sends a hardcoded placeholder sleep value; only the phone's Health Connect read is real.
- The current debug button simulates sensor values for testing without the smartwatch.
- Heart rate above 109 bpm is outside the training range, so the model extrapolates there.
- No Room database has been added yet, so nothing is stored: no history, no trends, no alerts.
- No Supabase/backend sync has been added yet. The app declares no `INTERNET` permission.
- No latency measurement exists yet, despite low latency being the project's central claim.
- `google-services.json` is absent, so Google sign-in currently fails with `DEVELOPER_ERROR`.
- The wearable payload uses AES/ECB with a hardcoded key duplicated in both modules, and
  `VitalReceiverService` logs the ciphertext. This needs replacing before it is presented as a
  security measure.
- No clinical validation has been performed.

## Suggested Next Steps

1. Run the instrumented parity test on a device or emulator
   (`./gradlew :app:connectedDebugAndroidTest`). It is written and builds, but has not yet been
   executed against real hardware.
2. Add a valid `app/google-services.json` so sign-in works.
3. Add local history storage using Room, with timestamps on every reading. Everything below
   depends on this.
4. Store prediction history with timestamp, profile snapshot, sensor values, per-model
   probabilities, and the model version already carried on each `StressPrediction`.
5. Add latency measurement across the BLE-to-alert path, which is the project's headline claim.
6. Add sustained high-stress alerts with smoothing and a cooldown.
7. Add stress trend charts to the dashboard.
8. Add an edit profile screen so different manual inputs can be tested without clearing app data.
9. Add final report screenshots and testing evidence.

## Build Notes

Open the project in Android Studio and build both modules:

- `app` for the phone.
- `wear` for the smartwatch.

For Google sign-in, keep a valid Firebase/Google configuration file at:

```text
app/google-services.json
```

For model inference, keep the ONNX bundle at:

```text
app/src/main/assets/stress_model/
```
