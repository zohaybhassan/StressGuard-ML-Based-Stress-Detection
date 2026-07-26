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

1. Health Services delivers a batch of vitals to `PassiveVitalsService` on the watch, whether or
   not the watch app is open. The newest usable heart rate is forwarded with the daily step total
   and the age of the sample.
2. `VitalReceiverService` on the phone stamps the arrival time, decrypts, and parses into a
   `SensorReading`. Implausible values are dropped; values outside the model's training ranges are
   kept and flagged, because live heart rate exceeds the trained maximum during ordinary activity.
3. The service hands the reading to `StressPipeline`, blocking until the pass completes so it is
   not torn down partway through.
4. Sleep comes from `SleepCache`, last written by the dashboard from Health Connect; a
   training-set-mean default is substituted if there is none, and labelled as assumed.
5. `StressFeatureBuilder` builds a 22-value vector in the order the model manifest declares.
6. `StressInferenceService` runs the three ONNX graphs and averages their probabilities.
7. The pipeline stores the prediction, pushes its class into the smoothing window, and asks
   `StressAlertManager` whether to alert.
8. `LatencyTracker` records the timings for the whole pass.
9. `DashboardViewModel` renders the result if a dashboard happens to be open. Nothing above this
   step depends on one existing.

## Background collection, and why there is no sampling interval

The app monitors with nothing open. That takes two APIs rather than one, and neither is a timer,
so the obvious assumption — that the app samples heart rate every *n* seconds — is wrong in a way
that changes how the numbers should be read.

| Path | API | When it runs | Cadence |
|---|---|---|---|
| Background | `PassiveMonitoringClient` → `PassiveVitalsService` | Always, app closed or killed | Batched by Health Services, minutes apart |
| Foreground | `MeasureClient` in `MainActivity` | Only while the watch app is open | Pushed per sample, floored at 5 s |

`MeasureClient` alone was the original design and it is unusable as a data source: it is the
**on-demand** API and measures only while an Activity is in the foreground, so closing the app or
letting the screen time out ended collection silently. A stress monitor the user has to hold open
measures nothing about their day.

`PassiveMonitoringClient` is the API for this. The registration is held by Health Services, not by
the app's process, so `PassiveVitalsService` is bound on demand to take delivery of each batch even
after the process has been killed. The cost is granularity: Health Services batches deliveries to
save power, so samples arrive every few minutes rather than every second. For stress that is the
right trade — the signal does not change second to second, and the optical sensor is the most
expensive component on the watch to run continuously. The alternative, a foreground service holding
`MeasureClient` open, buys per-second data for a permanent notification and heavy battery drain.

Consequences worth stating in the report:

- **Silence is normal, not exceptional.** Heart rate needs skin contact, so a watch off the wrist
  produces nothing, with no error anywhere.
- **A reading has an age.** A batched sample can be minutes old on arrival, so the watch sends the
  age it measured (`"<hr>|<steps>|<ageMs>"`) and the phone dates the reading from when the sensor
  took it. A duration, not a timestamp: the two devices do not share a clock, but an elapsed time
  measured on either means the same thing on both. Both UIs label a reading once it passes 30 s.
- **Registration is re-established** on every app start, on `BOOT_COMPLETED` and on
  `MY_PACKAGE_REPLACED`, because a passive registration does not reliably survive any of those.
- `DataType.STEPS_DAILY` supplies steps since midnight directly, which is what the model's "Daily
  Steps" feature means. `DailyStepCounter` exists only because `TYPE_STEP_COUNTER` counts from boot
  and needed a stored baseline; the passive path has no such problem.

Only a heart rate sample triggers a transmission. Step events update the watch display and ride
along with the next sample. Letting them transmit meant the payload — built from the last known
value of each field — re-sent a stale heart rate: the logs show 89 BPM sent five times across
70 seconds while the step count climbed from 16 to 57. The phone treats each message as an
independent reading, so that single measurement became five stored predictions, five latency
samples, and five entries in a five-slot alert window whose entire purpose is to require a
sustained trend.

## Why the pipeline is not in the ViewModel

`StressPipeline` is process-scoped and owns inference, storage, smoothing and alerting.
`VitalReceiverService` drives it directly; `DashboardViewModel` only renders what it produced.

This is required by background collection, not a matter of taste. When the work lived in the
ViewModel, a reading arriving with no dashboard open was received, published and then dropped —
and since most readings now arrive with the app closed, that is most readings. Two pieces of state
also have to outlive any Activity to be correct at all:

- the **smoothing window**, because "3 of the last 5 predictions are high stress" is meaningless if
  the window empties whenever the user closes the app; sustained stress across a morning has to be
  able to accumulate.
- the **loaded model**, roughly 13.8 MB of ONNX graphs, which would otherwise dominate the latency
  figures by reloading per reading.

Sleep is the one input the pipeline cannot read for itself. Health Connect is consulted by the
dashboard, so the figure is cached (`SleepCache`, expiring after 18 hours) for background
predictions to use. Without it every background prediction would substitute the training-set mean
and flatten a third of the live signal.

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
- **`checkSelfPermission` is not authoritative for health permissions.** On Wear OS 5+ these are
  managed by the Health Connect permission controller, not the platform runtime-permission store.
  The two can disagree: `dumpsys package` reporting
  `android.permission.health.READ_HEART_RATE: granted=true, flags=[USER_SET]` while Health Services
  logs `WHS_PermissionPolicy: SecurityException: ... doesn't have permission to access
  android.permission.health.READ_HEART_RATE`.

  `adb shell pm grant` produces exactly this split, and it is worse than useless during
  development: it writes to the platform store only, so `checkSelfPermission` returns GRANTED, the
  app therefore stops asking, and Health Services keeps refusing. Grant the permission through the
  app's own runtime request instead, and revoke any `pm grant` first or the request will never be
  shown. A reinstall revokes the real grant, so the dialog has to be answered again each time.

  The refusal is also not reported to the caller: `registerMeasureCallback` returns `void`, so the
  app cannot tell a permission problem apart from a watch that is not being worn. Passive
  registration is better behaved and does throw `SecurityException: Missing permissions`, which is
  why `PassiveVitals.register` returns a boolean the caller records.
- **Background heart rate is a second, separate grant**, and getting the wrong one is silent.
  A background permission is auto-denied without a dialog unless the app already holds the
  foreground permission it extends, so the pairing has to match: `BODY_SENSORS` with
  `BODY_SENSORS_BACKGROUND` up to API 34, `READ_HEART_RATE` with
  `READ_HEALTH_DATA_IN_BACKGROUND` from API 35. Requesting `BODY_SENSORS_BACKGROUND` on an API 36
  watch is refused in about four seconds with no UI, because `BODY_SENSORS` is capped at 34.

  Missing it does not fail at registration either: Health Services accepts the passive listener,
  delivers for a few minutes, then calls `onPermissionLost` — four minutes on this watch, which
  is indistinguishable from the watch being taken off.

  `pm list permissions` alone is not a reliable way to check what a platform supports; it omitted
  both `BODY_SENSORS_BACKGROUND` and `READ_HEALTH_DATA_IN_BACKGROUND` that `pm list permissions -g`
  then listed.
- Background readings are dropped rather than queued when the phone is out of Bluetooth range.
  A stress reading is only useful promptly, and a replayed one would be stamped with the wrong
  arrival time. Losing a window of readings is the accepted cost.
- Passive batches can arrive minutes apart, so the phone process is often killed in between and
  each batch pays the cold-start model load. This is why `LatencyMetricEntity.coldStart` is
  recorded separately — in background operation cold starts are common, not exceptional.
- Nothing syncs to Supabase yet except the profile, so local history stays on the device
- Dead UI declared in `activity_home_dashboard.xml` with no listeners: `btnEmergency`,
  `bottomNavigation`, and the `nav_trends` / `nav_assistant` menu entries
- Command-line Gradle builds require JDK 21; the machine's default JDK 26 is too new for
  Gradle 8.13's embedded Kotlin and fails with `IllegalArgumentException: 26.0.1`. Android
  Studio works because it uses its own bundled JDK at `D:\andriod\jbr`.
