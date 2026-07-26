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
2. `VitalReceiverService` stamps the arrival time, decrypts, and parses into a `SensorReading`.
   Implausible values are dropped; values outside the model's training ranges are kept and
   flagged, because live heart rate exceeds the trained maximum during ordinary activity.
3. The reading is published on `SensorRepository`, a `StateFlow`. Both the service and the
   dashboard are in one process, so no broadcast is involved.
4. Sleep data is requested from Health Connect when available, otherwise an assumed value is
   used so inference is not blocked.
5. `StressFeatureBuilder` builds a 22-value vector in the order the model manifest declares.
6. `StressInferenceService` runs the three ONNX graphs and averages their probabilities.
7. `DashboardViewModel` stores the prediction, pushes its class into the smoothing window, and
   asks `StressAlertManager` whether to alert.
8. `LatencyTracker` records the timings for the whole pass.

## Sampling cadence: nothing polls

There is no measurement interval, and this is worth stating plainly because the obvious
assumption — that the app samples heart rate every *n* seconds — is wrong in a way that changes
how the numbers should be read.

Three separate mechanisms decide when a reading reaches the phone:

| Mechanism | Behaviour |
|---|---|
| Health Services `MeasureClient` | Pushes heart rate samples on its own schedule. There is no API to request a rate. |
| `TYPE_STEP_COUNTER` at `SENSOR_DELAY_UI` | Fires only when the step count actually changes. |
| `MIN_SEND_INTERVAL_MS` (5 s) | A **floor** on transmissions. There is no ceiling. |

So the cadence is whatever the sensor offers, thinned to at most one message per five seconds.
Observed gaps on a Galaxy Watch 4 ranged from 5 s to 45 s. Two consequences follow:

- **Silence is normal, not exceptional.** Heart rate needs skin contact, and `MeasureClient`
  measures only while the watch app is in the foreground, so a screen timeout ends measurement.
  Either one stops the stream with no error anywhere.
- **A reading therefore has an age**, and both devices show it once it passes 30 s. Before this,
  the last value stayed on screen indefinitely and read as current.

Only a heart rate sample triggers a transmission. Step events update the watch display and ride
along with the next sample. Letting them transmit meant the payload — built from the last known
value of each field — re-sent a stale heart rate: the logs show 89 BPM sent five times across
70 seconds while the step count climbed from 16 to 57. The phone treats each message as an
independent reading, so that single measurement became five stored predictions, five latency
samples, and five entries in a five-slot alert window whose entire purpose is to require a
sustained trend.

## Latency: what is and is not measured

Timings run from the message arriving **on the phone** to the prediction, the UI update and the
haptic. Watch-to-phone transmission is not included and cannot be without synchronising the two
devices' clocks. Plan §12 names the first target "BLE receive to prediction"; what is measured
is *arrival on the phone* to prediction. Say so in the report rather than let the figure imply
end-to-end coverage.

Averages exclude the first inference of a process, which loads roughly 13.8 MB of ONNX graphs.
Cold and steady-state are stored separately (`LatencyMetricEntity.coldStart`) because an average
over both describes neither.

## Local storage

`StressGuardDatabase` (Room) holds predictions, latency samples and alert events. It is the
durable store for the real-time path: every write happens without a network, which is the point
of the architecture, and Supabase syncs *from* here rather than being written to directly.

Every table carries a `synced` flag with an `unsynced()` query, so the Phase 7 worker has a
queue to drain. Retention deletes rows older than 30 days **only if already synced**, so a long
spell offline cannot silently discard readings that never reached the backend.

Alert cooldown state is read from the database rather than memory, so it survives a restart and
cannot be bypassed by killing the app.

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

Every step above happens on device with no network. The design rule is the project's central
claim, so it is worth demonstrating in airplane mode rather than merely asserting.

## Planned Next Layers

- WorkManager sync worker to drain the `unsynced()` queues (the flags already exist)
- Health checklist and the rule-based recommendation module
- Trend charts over the stored prediction history
- Supportive chatbot backend
- Daily stress summaries

## Known Structural Gaps

- `EncryptionUtil.kt` is duplicated byte-for-byte across both modules, with a hardcoded
  AES/ECB key committed to the repository
- `AuthRepository.signOut()` has no caller; there is no sign-out anywhere in the UI
- Watch-side sleep is a hardcoded placeholder; only the phone's Health Connect read is real
- Heart rate is measured only while the watch app is in the foreground, because `MeasureClient`
  is the on-demand API. Continuous background measurement needs `PassiveMonitoringClient`, or an
  `ExerciseClient` behind a foreground service. Until then the watch screen must stay awake to
  collect a session.
- A **Health Services permission rejection is undetectable by the app.**
  `registerMeasureCallback` returns `void` and the refusal is logged in the Health Services
  system process (`WHS_PermissionPolicy: SecurityException ... doesn't have permission to access
  android.permission.health.READ_HEART_RATE`), not raised to the caller. Observed after a
  reinstall while `dumpsys package` reported `READ_HEART_RATE: granted=true` with `USER_SET`, so
  `checkSelfPermission` also said yes — the app cannot tell this apart from a watch that is
  simply not being worn. Rebooting the watch clears it. The 30-second staleness message is the
  only signal the app can give.
- The phone predicts only while the dashboard is open. `VitalReceiverService` receives and
  publishes regardless, but with no Activity there is no `DashboardViewModel`, so readings are
  neither predicted nor stored.
- Nothing syncs to Supabase yet except the profile, so local history stays on the device
- Dead UI declared in `activity_home_dashboard.xml` with no listeners: `btnEmergency`,
  `bottomNavigation`, and the `nav_trends` / `nav_assistant` menu entries
- Command-line Gradle builds require JDK 21; the machine's default JDK 26 is too new for
  Gradle 8.13's embedded Kotlin and fails with `IllegalArgumentException: 26.0.1`. Android
  Studio works because it uses its own bundled JDK at `D:\andriod\jbr`.
