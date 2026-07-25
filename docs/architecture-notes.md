# StressGuard Architecture Notes

## Current Shape

- Android app in Kotlin, phone module plus Wear OS companion
- Watch-to-phone delivery over the Google Play Services Wearable `MessageClient`
  (note: this is not raw BLE/GATT, despite the plan's wording -- worth stating precisely in
  the report, since "BLE pipeline" describes the transport inaccurately)
- Dashboard UI in XML views; the watch UI is Jetpack Compose
- Health Connect used for sleep access on phone
- Local encryption helper for watch payloads (AES/ECB, hardcoded key -- placeholder, not a
  defensible security design)
- On-device inference via ONNX Runtime: three tree models, equal-weight soft vote

## Present Screen Flow

- Launcher router
- Google sign-in screen
- Profile setup form
- Live dashboard

## Data Flow Today

1. Watch sends heart rate and step data.
2. `VitalReceiverService` decrypts and parses the message.
3. The dashboard receives a broadcast and updates the UI.
4. Sleep data is requested from Health Connect when available, otherwise an assumed value is
   used so inference is not blocked.
5. `StressFeatureBuilder` builds a 22-value vector in the order the model manifest declares.
6. `StressInferenceService` runs the three ONNX graphs and averages their probabilities.

## Model Contract

The exported bundle is the interface between the ML engine and the app, and the manifest is
what defines it. `StressModelInfo` reads from `stressguard_mobile_manifest.json`:

- `feature_names` -- the exact column order the vector must follow
- `n_classes` and `label.class_N` -- how many outputs and what they mean
- `ensemble.members[].inputs/outputs` -- each graph's ONNX input name and probability output
- `input_units` -- `raw_physical`: unscaled bpm, step counts, hours and years

None of this is duplicated in Kotlin. That is deliberate: an earlier build hardcoded the
feature order and fed raw units to models trained on z-scores, which produced a constant
prediction vector rather than any visible error. A mismatch between app and bundle now throws
instead of silently returning confident nonsense.

## Design Rule

Keep the real-time alert path fully local:

- receive from watch
- preprocessing
- inference
- UI update
- haptic alert

Anything network-dependent must happen after the alert path.

## Planned Next Layers

- Typed sensor models with timestamps and validation of impossible values
- Room storage for history and a sync queue -- nothing is persisted today
- Latency tracking across the receive-to-alert path
- Alert manager with smoothing and cooldown
- Supabase persistence
- Recommendation module
- Supportive chatbot backend

## Known Structural Gaps

- No persistence of any kind beyond `SharedPreferences`, so no history, trends or alert state
- No network layer and no `INTERNET` permission
- `EncryptionUtil.kt` is duplicated byte-for-byte across both modules
- Dead UI declared in `activity_home_dashboard.xml` with no listeners: `btnEmergency`,
  `bottomNavigation`, and the `nav_trends` / `nav_assistant` menu entries
- Command-line Gradle builds require JDK 21; the machine's default JDK 26 is too new for
  Gradle 8.13's embedded Kotlin and fails with `IllegalArgumentException: 26.0.1`. Android
  Studio works because it uses its own bundled JDK at `D:\andriod\jbr`.
