# StressGuard

StressGuard is an Android-based final year project for monitoring stress risk using a mobile app, a Wear OS companion app, wearable sensor data, user profile inputs, and an on-device machine learning pipeline.

The project currently focuses on a working prototype where the phone app signs in the user, collects profile information, receives heart rate and step data from the watch, reads sleep duration through Health Connect when available, and predicts a 3-class stress state using exported ONNX models.

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

- `relaxed_low_stress`
- `normal`
- `stressed_high`

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
- `google-services.json` is present inside the `app` module.
- Google account display name, email, and photo URL can be stored in `SessionManager`.
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

### Sensor Calibration Layer

During testing, the raw ONNX tree models sometimes returned identical probabilities for different sensor samples when the profile stayed the same. This can happen because Random Forest, XGBoost, and CatBoost are tree-based models, and different inputs can still land in the same decision leaves.

To make live wearable changes visible in the app's final stress output, `StressInferenceService.kt` currently applies a lightweight sensor-aware calibration after the ONNX ensemble output.

Current final prediction flow:

```text
Final prediction = ONNX ensemble average + sensor-aware calibration
```

This means:

- The ONNX models still provide the base prediction.
- Heart rate, steps, and sleep influence the final displayed stress result.
- The app logs both the raw model probabilities and the calibrated final probabilities for debugging.

Important note:

- The best long-term ML improvement is to retrain or re-export the models so the raw ONNX probabilities themselves become more responsive to sensor changes.

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
DebugFeatures=[...]
RandomForest probabilities=[...]
XGBoost probabilities=[...]
CatBoost probabilities=[...]
ModelAverage probabilities=[...]
SensorCalibration risk=...
Prediction=...
```

## Accuracy Notes

The ONNX manifest reports approximately:

```text
Test accuracy: 81.36%
Weighted F1: 81.39%
```

This accuracy belongs to the model evaluation dataset used during training/export. It should not be presented as guaranteed real-world medical accuracy.

Recommended wording for the project report:

```text
The trained model achieved approximately 81% test accuracy on the prepared dataset. The Android application integrates the exported ONNX ensemble with a sensor-aware calibration layer to produce real-time stress-risk estimates from user profile and wearable data.
```

StressGuard should be treated as a stress-risk monitoring prototype, not a clinical diagnosis tool.

## Folder Structure

```text
StressGuard/
├── app/
│   ├── build.gradle.kts
│   ├── google-services.json
│   └── src/
│       ├── androidTest/
│       │   └── java/com/example/stressguard/
│       │       └── ExampleInstrumentedTest.kt
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/
│       │   │   └── stress_model/
│       │   │       ├── catboost.onnx
│       │   │       ├── random_forest.onnx
│       │   │       ├── stressguard_mobile_manifest.json
│       │   │       └── xgboost.onnx
│       │   ├── java/com/example/stressguard/
│       │   │   ├── EncryptionUtil.kt
│       │   │   ├── HomeDashboardActivity.kt
│       │   │   ├── LoginActivity.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── ProfileSetupActivity.kt
│       │   │   ├── SessionManager.kt
│       │   │   ├── StressFeatureBuilder.kt
│       │   │   ├── StressInferenceService.kt
│       │   │   └── VitalReceiverService.kt
│       │   └── res/
│       │       ├── drawable/
│       │       │   ├── bg_metric_heart.xml
│       │       │   ├── bg_metric_sleep.xml
│       │       │   └── bg_metric_steps.xml
│       │       ├── layout/
│       │       │   ├── activity_home_dashboard.xml
│       │       │   ├── activity_login.xml
│       │       │   ├── activity_main.xml
│       │       │   └── activity_profile_setup.xml
│       │       ├── menu/
│       │       │   └── bottom_nav_menu.xml
│       │       ├── values/
│       │       │   ├── colors.xml
│       │       │   ├── strings.xml
│       │       │   └── themes.xml
│       │       ├── values-night/
│       │       │   └── themes.xml
│       │       └── xml/
│       │           ├── backup_rules.xml
│       │           └── data_extraction_rules.xml
│       └── test/
│           └── java/com/example/stressguard/
│               └── ExampleUnitTest.kt
├── wear/
│   ├── build.gradle.kts
│   ├── lint.xml
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── java/com/example/stressguard/presentation/
│           │   ├── EncryptionUtil.kt
│           │   ├── MainActivity.kt
│           │   └── theme/
│           │       └── Theme.kt
│           └── res/
│               ├── drawable/
│               ├── mipmap-anydpi/
│               ├── values/
│               └── values-round/
├── docs/
├── gradle/
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── local.properties
├── README.md
└── settings.gradle.kts
```

## Key Files

- `app/src/main/java/com/example/stressguard/MainActivity.kt`: routes user to login, profile setup, or dashboard.
- `app/src/main/java/com/example/stressguard/LoginActivity.kt`: handles Google sign-in.
- `app/src/main/java/com/example/stressguard/ProfileSetupActivity.kt`: saves user profile details.
- `app/src/main/java/com/example/stressguard/SessionManager.kt`: stores session, Google account, and profile data.
- `app/src/main/java/com/example/stressguard/HomeDashboardActivity.kt`: dashboard UI logic, live data display, sleep read path, and debug testing.
- `app/src/main/java/com/example/stressguard/VitalReceiverService.kt`: receives wearable messages on the phone.
- `app/src/main/java/com/example/stressguard/StressFeatureBuilder.kt`: builds the 22-feature model input vector.
- `app/src/main/java/com/example/stressguard/StressInferenceService.kt`: runs ONNX inference and sensor calibration.
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

- Sleep data depends on Health Connect availability and whether a provider has written sleep records.
- The current debug button simulates sensor values for testing without the smartwatch.
- The ONNX tree models may return identical raw probabilities for different sensor values under the same fixed profile.
- The app-side sensor calibration improves responsiveness, but proper model retraining is the stronger long-term solution.
- No Room database has been added yet.
- No Supabase/backend sync has been added yet.
- No clinical validation has been performed.

## Suggested Next Steps

1. Add an edit profile screen so different manual inputs can be tested without clearing app data.
2. Add local history storage using Room.
3. Store prediction history with timestamp, profile snapshot, sensor values, raw model probabilities, and final calibrated result.
4. Improve the ML dataset and retrain models so sensor features influence raw ONNX probabilities more strongly.
5. Add stress trend charts to the dashboard.
6. Add sustained high-stress alerts and recommendations.
7. Add final report screenshots and testing evidence.

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
