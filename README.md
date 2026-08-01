# StressGuard

StressGuard is an Android-based final year project for monitoring stress risk using a mobile app, a Wear OS companion app, wearable sensor data, user profile inputs, and an on-device machine learning pipeline.

The project currently focuses on a working prototype where the phone app signs in the user, collects profile information, receives heart rate and step data from the watch, reads sleep duration through Health Connect when available, and predicts a binary stress state using exported ONNX models.

## Current Status

The project has two Android modules:

- `app`: the mobile Android application.
- `wear`: the Wear OS companion application.

The mobile app is currently functional for:

- Google sign-in through Credential Manager, backed by Supabase auth.
- Profile setup and a health checklist.
- Dashboard display, including a rule-based checkup recommendation.
- Background watch data reception, with the app closed.
- Basic encrypted wearable data transfer.
- Health Connect sleep read path.
- ONNX model inference on device.
- Local history in Room: predictions, latency samples, alerts.
- Sustained-stress haptic alerts with smoothing and a cooldown.
- Post-alert user confirmation with an optional 1-10 severity label for future model evaluation.
- Temporary alert muting for 10 minutes, 30 minutes, 1 hour, or 4 hours.
- A trends screen: weekly stress chart plus heart rate, sleep and activity over time.
- Background sync of local history to Supabase.
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

1. `MainActivity` starts first and waits for Supabase to restore a stored session before routing.
2. If the user is not signed in, the app opens `LoginActivity`.
3. `LoginActivity` signs the user in or registers them, by email and password or through Google.
   Either way the result is a Supabase session, which persists until they sign out.
4. `PostAuthRouter` decides where a signed-in user goes: `SetPasswordActivity` if the account has
   no password yet, otherwise the profile form or the dashboard. Shared by every entry point, so
   the same user cannot get a different destination depending on which door they came through.
   Before opening the dashboard it restores the authenticated user's latest 14 days of synced
   prediction history from Supabase into Room, which restores Trends after logout or reinstall.
5. `ProfileSetupActivity` saves profile values such as name, age, gender, occupation, and BMI
   category. A missing local profile is first recovered from Supabase, so a reinstall does not
   re-ask.
6. `HealthChecklistActivity` collects self-reported risk factors. Skippable.
7. `HomeDashboardActivity` displays heart rate, steps, sleep, connection state, stress prediction,
   latency, sync status and the checkup recommendation.
8. `VitalReceiverService` receives encrypted watch data whether or not any Activity exists, and
   hands it to `StressPipeline`.
9. `StressPipeline` owns inference, storage, smoothing and alerting; it is process-scoped rather
   than owned by the dashboard, because most readings now arrive with the app closed.
10. `StressInferenceService` runs the ONNX ensemble and returns the stress prediction.

## Implemented Features

### Authentication

**Signing up goes through Google, then the user chooses a password.** Signing in afterwards works
with either.

```text
FIRST RUN            Continue with Google        (Credential Manager -> Supabase session)
                              |
                     Choose a password           (SetPasswordActivity, not skippable)
                              |
                     Profile -> Checklist -> Dashboard

RETURNING            session restored -> straight to the dashboard
after Sign out       email + password   OR   Continue with Google
```

The password step is not decoration. **A Supabase account created through Google OAuth has an empty
`encrypted_password`**, so signing in with that same address and any password fails forever with
"Invalid login credentials". Offering both routes on the login screen without this step would mean
the email half never worked for anyone. `AuthRepository.setPassword` calls `auth.updateUser` while
the Google session is live, which is why it happens immediately after sign-up rather than from a
settings screen later.

Whether an account still needs one is tracked in `profiles.password_set`, set by the signup trigger
from `auth.users.encrypted_password` — so email sign-ups start as `true` and never see the screen.
A column rather than `user.identities`, which depends on GoTrue internals, or a local flag, which
would not survive a reinstall. `PostAuthRouter` caches a `true` locally so the check is not a
network round trip on every launch; a `false` is never cached, so the step cannot be skipped by a
stale flag.

Registering with **email and password** directly is still available as a fallback, so a device with
no Google account is not locked out. It is presented as the secondary option, because an account
made that way has only one route in.

Google goes through **Credential Manager** (`signInWith(IDToken)`). The deprecated
`GoogleSignInClient` from `play-services-auth` was removed and `google-services.json` is not used at
all. The button is hidden when `supabase.googleWebClientId` is unset, so an email-only project is
still fully usable.

**Sessions persist until sign-out.** The Supabase client writes the session to storage, reloads it
on launch and refreshes the access token in the background; `MainActivity` waits for that restore
before routing, so a returning user never sees the login screen. `SupabaseProvider` sets
`autoSaveToStorage` / `autoLoadFromStorage` / `alwaysAutoRefresh` explicitly rather than relying on
defaults, because this is behaviour the app promises.

**Sign out** lives in the dashboard toolbar's overflow menu. It ends the Supabase session *and*
clears local user data — see `LocalUserData`. That second half is not tidiness: none of the local
storage is keyed by user, and `SupabaseSyncWorker` stamps whatever is queued with whoever is signed
in **at upload time**, so a previous user's readings would otherwise be uploaded into the next
user's account. The confirmation dialog reports how many rows have not yet reached the server,
since those are discarded.

If your Supabase project has **Confirm email** enabled (the default), the *email* registration
fallback does not sign the user in — `signUpWithEmail` returns `ConfirmationEmailSent` and the
screen asks them to click the emailed link first. Turn it off under Authentication → Sign In /
Providers → Email for that route to log straight in. Google sign-up is unaffected.

Configuration comes from `local.properties` via `BuildConfig` — see
[supabase.properties.template](supabase.properties.template). Missing values do not fail the build;
`LoginActivity` lists what is missing instead.

### Profile Setup

The app stores the following user profile fields:

- Name
- Age
- Gender
- Occupation
- BMI category

These values are saved locally through `SessionManager` and are used by the stress feature builder.

**BMI category** is a self-reported label, not a calculation — the app never asks for height or
weight. The four options come from `ml_engine/data/sleep_health_dataset.csv`, which carries them as
bare labels with no BMI number anywhere, so the dataset documents no thresholds of its own. The
dropdown therefore shows the **WHO** cut-offs alongside each label so that two users with the same
body pick the same category:

| Label | BMI |
|---|---|
| Underweight | under 18.5 |
| Normal | 18.5 – 24.9 |
| Overweight | 25.0 – 29.9 |
| Obese | 30.0 and over |

`Normal` is the `drop_first` baseline, so it is represented by all-zero one-hot flags rather than a
column of its own; the other three are features 20-22.

### Wearable Data

The Wear OS module collects and sends wearable data to the phone.

Currently supported sensor values:

- Heart rate
- Step count

The phone receives these values through the Google Play Services Wearable APIs.

### Sleep Data

Sleep duration is read through Health Connect on the phone when permission and data are available.
Records ending on the same local date form one sleep day: nearby fragments are joined, the longest
group is shown as the main sleep, and later naps are displayed separately but added to the total
given to the stress model. This prevents an evening nap from replacing the preceding night's sleep.

Tapping the dashboard sleep card opens a detail screen with session times, naps, available sleep
stages, and the average oxygen saturation recorded during those sleep sessions. Oxygen access is
read-only, requested on that screen, and oxygen is not currently used by the stress model.
The same screen has a persistent 4-12 hour sleep goal, adjustable in 15-minute increments. The goal
changes progress tracking only; it never replaces or alters the Health Connect measurement.

Current limitation:

- Sleep may not appear if Health Connect has no sleep records from the watch or another health provider.
- For model testing, the dashboard includes simulated sleep values.

### Wearable Payload Encryption

Basic encryption/decryption support exists in both modules:

- Mobile: `EncryptionUtil.kt`
- Wear: `EncryptionUtil.kt`

This obscures the wearable payload before it is processed on the mobile side. It is **not** a
defensible security design: AES/ECB with a 16-byte key committed to the repository and duplicated
byte-for-byte across both modules. Say so rather than presenting it as a security measure.

Note that the transport is the Wear OS message channel (Google Play Services `MessageClient`), not
BLE/GATT — see [docs/model-limitations.md](docs/model-limitations.md).

### Health Checklist and Checkup Recommendation

`HealthChecklistActivity` collects eight self-reported risk factors (smoking, heart condition,
hypertension, diabetes, sleep disorder, anxiety history, caffeine use, physical inactivity). It is
skippable, stored locally in Room, and synced to Supabase.

`RecommendationPolicy` combines those answers with the stored prediction history into a rule-based
risk score of 0-100 and one of four actions. It is pure and exhaustively unit-tested. The dashboard
card shows the score, its band, every contributing factor with the points it added, and a standing
disclaimer that the app does not diagnose.

### Dashboard UI

The dashboard currently shows:

- Welcome text
- Watch connection status, distinguishing "no watch" from "watch present but not sending"
- Heart rate, steps and sleep duration, with the age of the reading once it goes stale
- Stress percentage, status label and circular gauge
- Measured latency, and the last alert or why one was suppressed
- Sync status, tappable to sync now
- The checkup recommendation card
- Debug model test button

The step card opens a dedicated activity view with today's progress, a persistent 2,000-20,000
step goal, a seven-day bar chart backed by `daily_step_totals`, and a dashed target line. The sleep
card opens the sleep-day detail described above. Settings is available from the dashboard overflow
menu and provides profile editing, both goals, health-checklist editing, Health Connect access,
alert pause controls, Android notification settings, and the app's local-data/privacy summary.

### Alert Feedback and Retraining Data

When a real sustained-high-stress alert fires, the app creates a pending `stress_feedback` row and
the notification offers **Check in**, **Mute alerts**, and **Talk it through** actions. The check-in
asks whether the user was actually stressed; a confirmed response also records severity on the
original dataset's 1-10 scale. The shipped model remains binary: source scores of 7-10 map to
`stressed`, while 1-6 map to `not_stressed`.

Each completed response keeps the alert-time model version, probabilities, sensor inputs and
profile snapshot. Completed rows sync separately to Supabase; unanswered prompts and simulated
debug alerts do not enter the retraining dataset.

Alert-triggered feedback alone is suitable for measuring precision and false positives, but not
false negatives: it never asks when the model predicts low stress. Before full retraining, add a
small number of periodic check-ins and evaluate with user-grouped train/test splits so one person's
records cannot appear in both sets.

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

**Do not quote that figure without the limitations.** They are set out with measurements in
[docs/model-limitations.md](docs/model-limitations.md), and the most important one is that
accuracy is not the whole story:

- **Occupation influences the prediction more than the live wearable data does** — median swing
  0.754 in `P(stressed)` versus 0.458 for heart rate, steps and sleep combined. For 6 of the 15
  occupations, no combination of vitals in the trained range changes the predicted class. This
  follows from the dataset, where occupation is close to a proxy for the stress label. Measure it
  with `python ml_engine/analyze_feature_influence.py`.
- The training data is a survey of *resting* heart rate spanning 43-109 bpm. Live wearable heart
  rate exceeds 109 during ordinary activity, where the trees clamp rather than extrapolate.
  Affected predictions are flagged and stored as such.
- Oversampling is applied before the train/test split, so synthetic rows derived from test-fold
  neighbours reach the training set. This inflates the reported figure by an unquantified amount.
  Kept deliberately so the number stays comparable to earlier write-ups.

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
  app/                                  phone module (XML views)
    build.gradle.kts
    schemas/                            Room's exported schema JSON, committed
    src/
      androidTest/                      instrumented tests (need a device)
        assets/parity_samples.json      generated by ml_engine
        java/com/example/stressguard/
          StressInferenceParityTest.kt  Python-vs-Android probability parity
          data/local/StressGuardDatabaseTest.kt
          data/local/StressGuardMigrationTest.kt
      main/
        AndroidManifest.xml
        assets/stress_model/            catboost | random_forest | xgboost .onnx + manifest
        java/com/example/stressguard/
          MainActivity.kt               splash router
          LoginActivity.kt
          ProfileSetupActivity.kt
          HealthChecklistActivity.kt
          HomeDashboardActivity.kt
          DashboardViewModel.kt
          VitalReceiverService.kt       receives watch messages, app open or not
          StressInferenceService.kt     ONNX soft-vote ensemble
          StressModelInfo.kt            parses the bundle manifest
          StressFeatureBuilder.kt       22-feature vector, manifest order
          SessionManager.kt
          EncryptionUtil.kt
          data/
            StressPipeline.kt           process-scoped: inference, storage, smoothing, alerting
            SensorReading.kt            typed reading + validation
            StepHistory.kt              activity level, not steps-since-midnight
            SleepCache.kt
            LatencyTracker.kt
            StressAlertPolicy.kt        pure: 3-of-5 smoothing, 10 min cooldown
            StressAlertManager.kt       haptics, notification, alert record
            DailyStressSummary.kt       pure: per-day rollup of stored predictions
            RecommendationPolicy.kt     pure: rule-based checkup score (plan section 7)
            RecommendationRepository.kt
            HealthChecklistRepository.kt
            AuthRepository.kt           Credential Manager -> Supabase session
            ProfileRepository.kt
            SupabaseConfig.kt           validates keys, detects a leaked secret key
            SupabaseProvider.kt
            local/                      Room: entities, DAOs, database, migrations
            sync/                       WorkManager sync worker, wire rows, scheduler
        res/layout/                     activity_{main,login,profile_setup,health_checklist,home_dashboard}.xml
      test/java/com/example/stressguard/  JVM unit tests
  wear/                                 Wear OS module (Jetpack Compose)
    src/main/java/com/example/stressguard/presentation/
      MainActivity.kt                   foreground MeasureClient path
      PassiveVitals.kt                  Health Services passive registration
      PassiveVitalsService.kt           background delivery, app closed
      PassiveVitalsStore.kt             send throttle, survives the process
      DailyStepCounter.kt
      PassiveVitalsBootReceiver.kt
      EncryptionUtil.kt
  ml_engine/                            Python: training, tuning, ONNX export, analysis
    data/                               raw dataset and prepared training sets
    mobile_export/                      four bundles; binary_voting_top3_raw ships
    artifacts_tuned_*/                  tuning reports (joblib files are gitignored)
  supabase/migrations/                  SQL schema and RLS policies
  docs/                                 architecture-notes.md, model-limitations.md
  supabase.properties.template          backend config template
  build.gradle.kts, settings.gradle.kts, gradle/, gradlew
```

## Key Files

- `app/src/main/java/com/example/stressguard/MainActivity.kt`: routes user to login, profile setup, or dashboard.
- `app/src/main/java/com/example/stressguard/LoginActivity.kt`: sign in or register, by email or Google.
- `app/src/main/java/com/example/stressguard/SetPasswordActivity.kt`: gives a Google account a password.
- `app/src/main/java/com/example/stressguard/PostAuthRouter.kt`: where a signed-in user goes next.
- `app/src/main/java/com/example/stressguard/ProfileSetupActivity.kt`: saves user profile details.
- `app/src/main/java/com/example/stressguard/HealthChecklistActivity.kt`: self-reported risk factors.
- `app/src/main/java/com/example/stressguard/SessionManager.kt`: local profile storage.
- `app/src/main/java/com/example/stressguard/HomeDashboardActivity.kt`: dashboard UI logic, live data display, sleep read path, and debug testing.
- `app/src/main/java/com/example/stressguard/VitalReceiverService.kt`: receives wearable messages on the phone.
- `app/src/main/java/com/example/stressguard/data/StressPipeline.kt`: reading in, prediction out — stored, timed and possibly alerted.
- `app/src/main/java/com/example/stressguard/TrendsActivity.kt`: weekly stress and vitals charts.
- `app/src/main/java/com/example/stressguard/data/TrendsRepository.kt`: the per-day rollup the charts draw.
- `app/src/main/java/com/example/stressguard/data/PredictionHistoryRepository.kt`: paged, authenticated recent-history restoration after login.
- `app/src/main/java/com/example/stressguard/data/StressAlertPolicy.kt`: pure smoothing and cooldown rule.
- `app/src/main/java/com/example/stressguard/data/RecommendationPolicy.kt`: pure rule-based checkup score.
- `app/src/main/java/com/example/stressguard/data/local/StressGuardDatabase.kt`: Room store and its migrations.
- `app/src/main/java/com/example/stressguard/data/sync/SupabaseSyncWorker.kt`: drains the local queues to Supabase.
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
- Health Connect client
- Lifecycle Runtime/ViewModel KTX
- ONNX Runtime Android
- Supabase Kotlin (auth + postgrest), with the Ktor OkHttp engine
- AndroidX Credential Manager + Google ID (replaces the deprecated `play-services-auth`)
- Room (runtime, ktx, compiler via KSP)
- WorkManager
- Kotlin coroutines, kotlinx.serialization

Wear app:

- Wearable APIs
- Jetpack Compose for Wear UI
- AndroidX Health Services client
- Core SplashScreen

## Known Limitations

- Sleep data depends on Health Connect availability and whether a provider has written sleep
  records. When none exist the app falls back to an assumed 7.5 hours, labelled as such in
  the UI, so inference still runs instead of stalling.
- Sleep never comes from the watch, and cannot — Wear OS Health Services exposes no sleep data
  type. Only the phone's Health Connect read is real.
- The current debug button simulates sensor values for testing without the smartwatch. Note that
  for about half of profiles all three scenarios land on the same class, because occupation
  dominates — see [docs/model-limitations.md](docs/model-limitations.md).
- Heart rate above 109 bpm is outside the training range, so the model clamps rather than
  extrapolates. Such predictions are flagged with an asterisk and recorded as out-of-range.
- The recommendation is rule-based and self-report driven. It suggests a routine checkup; it does
  not diagnose, and two collected answers (sleep disorder, caffeine use) score nothing because the
  plan's point table gives them no weight.
- There is no password reset flow. A user who forgets their password has to be reset from the
  Supabase dashboard.
- The wearable payload uses AES/ECB with a hardcoded key duplicated in both modules. This needs
  replacing before it is presented as a security measure.
- `btnEmergency` and the Trends/Assistant tabs are visible but not wired to anything.
- No clinical validation has been performed.

## Suggested Next Steps

1. Build the supportive chatbot: a Supabase Edge Function wrapping Hugging Face, keeping the token
   server-side, plus the screen, a quick action from a high-stress alert, and restoring the
   `nav_assistant` tab that was removed until it exists.
2. Collect the 30 latency samples the plan asks for, with the network on and in airplane mode.
3. Decide what `btnEmergency` should do, or remove it.
4. Replace the AES/ECB hardcoded-key wearable encryption before presenting it as a security
   measure.
5. Add final report screenshots and testing evidence.

## Testing

```bash
./gradlew :app:test                        # 143 unit tests
ANDROID_SERIAL=<phone> ./gradlew :app:connectedDebugAndroidTest   # 29 instrumented tests
```

Point the instrumented run at the phone. Gradle installs `:app` on every connected device including
a paired watch, where an Activity test cannot resume and will fail for reasons that have nothing to
do with the code.

Command-line Gradle builds require **JDK 21**; JDK 26 fails Gradle 8.13's embedded Kotlin with
`IllegalArgumentException: 26.0.1`. Android Studio works because it uses its own bundled JDK.

The instrumented suite covers the Python-vs-Android parity of the shipped ONNX bundle
(`StressInferenceParityTest`), the Room store, the database migrations, the trends screen, and the
sign-out data wipe. All 29 have been run on an Android 15 phone; the non-UI subset also passes on a
Galaxy Watch 4 (Wear OS 6 / API 36).

## Build Notes

Open the project in Android Studio and build both modules:

- `app` for the phone.
- `wear` for the smartwatch.

For sign-in and sync, copy the keys from
[supabase.properties.template](supabase.properties.template) into `local.properties` and fill them
in. `google-services.json` is **not** used — sign-in goes through Credential Manager and Supabase.
A build with the values missing still compiles and runs; the login screen lists what is absent.

Apply the SQL in `supabase/migrations/` to your Supabase project, in filename order.

For model inference, keep the ONNX bundle at:

```text
app/src/main/assets/stress_model/
```

From the command line, use JDK 21 — see [Testing](#testing).
