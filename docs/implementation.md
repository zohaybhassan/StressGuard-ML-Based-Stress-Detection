# StressGuard: Implementation Reference

Everything the system does, why it does it that way, and where it falls short. Written to be read
alongside the code rather than instead of it — every claim here should be checkable against a file,
and file paths are given throughout.

Companion documents: [`architecture-notes.md`](architecture-notes.md) for the reasoning behind
individual design decisions, and [`model-limitations.md`](model-limitations.md) for the ML caveats
in depth.

**Contents**

1. [What the system is](#1-what-the-system-is)
2. [Machine learning: data and training](#2-machine-learning-data-and-training)
3. [Model export and mobile inference](#3-model-export-and-mobile-inference)
4. [The watch: sensing and background collection](#4-the-watch-sensing-and-background-collection)
5. [Transport: watch to phone](#5-transport-watch-to-phone)
6. [The phone: the prediction pipeline](#6-the-phone-the-prediction-pipeline)
7. [Feature construction, and two defects it survived](#7-feature-construction-and-two-defects-it-survived)
8. [Latency: what was measured](#8-latency-what-was-measured)
9. [Battery and power](#9-battery-and-power)
10. [Local storage](#10-local-storage)
11. [Alerts](#11-alerts)
12. [Backend: Supabase](#12-backend-supabase)
13. [Health checklist and the checkup recommendation](#13-health-checklist-and-the-checkup-recommendation)
14. [Trends](#14-trends)
15. [The supportive chatbot](#15-the-supportive-chatbot)
16. [Explaining a prediction](#16-explaining-a-prediction)
17. [Security and privacy](#17-security-and-privacy)
18. [Testing](#18-testing)
19. [Limitations](#19-limitations)
20. [Build and deploy](#20-build-and-deploy)

---

## 1. What the system is

Two Android modules and a backend.

| Part | Path | Runs on |
|---|---|---|
| Wear OS app | `wear/` | Galaxy Watch 4, Wear OS 6 / API 36 |
| Phone app | `app/` | vivo V27e, Android 15 / API 35 |
| Edge Function | `supabase/functions/chat/` | Supabase (Deno) |
| Database | `supabase/migrations/` | Supabase Postgres |

`compileSdk 36` on both modules; `minSdk 26` on the phone, `minSdk 30` on the watch.

**The central architectural claim:** stress detection is entirely on-device. Sensor reading →
feature vector → ONNX inference → alert never touches the network. The backend is for durability,
long-term analysis and the chatbot, and it is deliberately outside the alert path. If the network
were required, the low-latency claim would collapse the moment a user walked into a lift.

---

## 2. Machine learning: data and training

### Source data

`ml_engine/data/sleep_health_dataset.csv` — the Sleep Health and Lifestyle dataset. Each row is a
**person**, not a moment in time. That distinction turns out to matter enormously (see
[§7](#7-feature-construction-and-two-defects-it-survived)).

### Class balancing with SMOTENC

`ml_engine/prepare_dataset.py`.

The original pipeline used plain SMOTE, which interpolates between neighbours in continuous space.
Applied to one-hot encoded columns it produces values like `Occupation_Nurse = 0.37` — a person who
is 37% nurse. Rounding those back to 0/1 created rows that were simultaneously two occupations, or
none.

**504 of 2360 rows (21.4%) were impossible combinations.**

SMOTENC is the variant designed for mixed nominal and continuous data: it interpolates the
continuous columns and takes the *majority category* among neighbours for the nominal ones, so a
synthetic row is always exactly one occupation.

One subtlety inside the resampler: neighbour geometry is computed on standardised columns, because
Daily Steps ranges 1000–16036 while Sleep Duration ranges 5.1–10.0. Left raw, step count would
dominate every distance calculation and sleep would effectively not participate in choosing
neighbours. The standardisation is inverted immediately afterwards, so the file on disk is in raw
physical units.

Output: `ml_engine/data/StressGuard_Iteration1_Raw_Units.csv`, 2360 rows, 22 features.

The script asserts the column count matches the manifest and that zero impossible combinations
remain, so the corruption cannot silently return.

### Features (22)

| Kind | Columns |
|---|---|
| Continuous | Age, Heart Rate, Daily Steps, Sleep Duration |
| Gender | `Gender_Male` |
| Occupation | 14 one-hot columns |
| BMI | `BMI Category_Obese`, `_Overweight`, `_Underweight` |

One-hot encoding uses `drop_first=True`, so **Female, Accountant and Normal BMI have no column of
their own** — they are represented by every flag in their group being zero. This is the model's
implicit baseline and is used deliberately in [§16](#16-explaining-a-prediction).

Training ranges, which the app checks every reading against:

```
Heart Rate      43 – 109 bpm      median 75
Daily Steps     1000 – 16036      median 5840
Sleep Duration  5.1 – 10.0 hours  median 7.9
Age             18 – 80           median 47
```

### Model

`ml_engine/train_ml_engine.py`, `ml_engine/export_mobile_model.py`.

A **soft-voting ensemble with equal weights** over three tree models:

- RandomForest
- XGBoost
- CatBoost

Each produces a probability vector; the three are averaged element-wise and the argmax taken.
Soft voting rather than hard voting keeps the confidence figure meaningful.

### Binary versus 3-class

Both were trained and evaluated. Binary ships.

| Bundle | Accuracy | F1 (weighted) | High-stress recall |
|---|---|---|---|
| **Binary** (`not_stressed` / `stressed`) | **0.8877** | **0.8878** | **0.8624** |
| 3-class | 0.7797 | 0.7800 | 0.8014 |

The binary label is a threshold at stress level 7 (`binary_threshold: 7` in the manifest). Plan §2
recommends starting binary and adding three levels only once the MVP is stable; the ten-point
accuracy gap made that easy to accept.

Per-class figures for the shipped bundle (`reported_voting_metrics` in the manifest):

```
              precision  recall  f1     support
not_stressed  0.902      0.908   0.905  283
stressed      0.861      0.852   0.856  189
```

---

## 3. Model export and mobile inference

### Export

`ml_engine/export_mobile_model.py` writes to `app/src/main/assets/stress_model/`:

| File | Size | Converter |
|---|---|---|
| `random_forest.onnx` | 12.3 MB | `skl2onnx` (emits a ZipMap output) |
| `xgboost.onnx` | 384 KB | `onnxmltools` |
| `catboost.onnx` | 1.1 MB | `onnxmltools` |
| `stressguard_mobile_manifest.json` | 4.8 KB | written by the exporter |

**13.8 MB total.** RandomForest dominates because a forest serialises every split of every tree.

### The manifest is the contract

`StressModelInfo.kt` reads it at runtime. It carries the feature names **in order**, the class
labels, the class count, the ensemble members and their ONNX input/output names, and
`input_units: "raw_physical"`.

Feature order comes from the manifest rather than being hardcoded in Kotlin. Re-exporting a bundle
with different columns then fails loudly at load instead of silently misaligning the vector — which
is the kind of bug that produces confident, wrong predictions with no error anywhere.

### Inference

`StressInferenceService.kt`, ONNX Runtime Android 1.20.0.

Three sessions are created lazily and kept for the life of the process. Each returns a probability
vector; they are averaged and the argmax taken, exactly as `kotlin_inference` in the manifest
describes.

### Python-to-Android parity

`ml_engine/make_parity_samples.py` writes 20 fixed samples to
`app/src/androidTest/assets/parity_samples.json` with the probabilities Python produced.
`StressInferenceParityTest` replays them through the Android bundle.

**Result: maximum probability difference 2.3 × 10⁻⁷, zero class disagreements.**

That is float32 rounding, not a discrepancy. It is the evidence that the exported bundle is the
model that was evaluated.

---

## 4. The watch: sensing and background collection

### Two APIs, and why both

| Path | API | When | Cadence |
|---|---|---|---|
| Background | `PassiveMonitoringClient` → `PassiveVitalsService` | Always, app closed or killed | Batched, minutes apart |
| Foreground | `MeasureClient` in `MainActivity` | Only while the watch app is open | ~1 Hz, floored at 5 s |

`MeasureClient` alone was the original design and it is **unusable as a data source**. It is the
on-demand API and measures only while an Activity is in the foreground, so closing the app or
letting the screen time out ended collection silently. A stress monitor the user has to hold open
measures nothing about their day.

`PassiveMonitoringClient` fixes that. The registration is held by Health Services, not by the app's
process, so `PassiveVitalsService` is bound on demand to take delivery of each batch even after the
process has been killed. Registration is re-established on every app start, on `BOOT_COMPLETED` and
on `MY_PACKAGE_REPLACED`, because a passive registration does not reliably survive any of those
(`PassiveVitalsBootReceiver.kt`).

Data types requested: `HEART_RATE_BPM` and `STEPS_DAILY`.

### Measured background behaviour

From a live run with the watch app force-stopped:

```
20:50:59  batch of 79 samples,  newest 43 s old
20:54:17  batch of 124 samples, newest 115 s old
21:15:06  batch of 512 samples, newest 6 s old
```

Batches arrive roughly **every 1.5 – 5.5 minutes**. Each carries dozens to hundreds of samples,
which means Health Services *collects* at about 1 Hz continuously and only *delivers* in bursts.
The app forwards the newest sample from each batch.

### Steps

`DataType.STEPS_DAILY` gives steps since midnight directly. `DailyStepCounter.kt` exists only
because `TYPE_STEP_COUNTER` (the foreground path) counts from boot and needs a stored baseline.

Both sources write to `PassiveVitalsStore`, and a plain setter let the weaker one overwrite the
stronger: a passive batch recorded 4010 steps and seconds later the foreground path wrote 0 over it,
because its baseline had been reset by a reinstall. The store now keeps the **maximum within a day**,
so arrival order stops mattering, and resets across a day boundary so yesterday's total cannot stand
in for today's forever.

### Permissions

Heart rate access changed twice under this project's feet.

| API level | Foreground | Background |
|---|---|---|
| ≤ 34 | `BODY_SENSORS` | `BODY_SENSORS_BACKGROUND` |
| ≥ 35 | `android.permission.health.READ_HEART_RATE` | `android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND` |

Three things cost real debugging time and are worth knowing:

1. **`BODY_SENSORS` does not appear in settings on API 35+.** Being told to "grant it in Settings"
   led nowhere, because the platform no longer offers it.
2. **A background permission is auto-denied — no dialog, refused in about four seconds — unless the
   app already holds the foreground permission it extends.** `BODY_SENSORS_BACKGROUND` extends
   `BODY_SENSORS`, which the app deliberately caps at API 34, so it can never be granted on a
   modern watch. Pairing it correctly is what made the dialog appear.
3. **Missing the background grant does not fail at registration.** Health Services accepts the
   listener, delivers for a few minutes, then calls `onPermissionLost` — four minutes on this watch,
   which is indistinguishable from the watch being taken off.

Also: `adb shell pm grant` for health permissions writes only to the platform store, so
`checkSelfPermission` returns GRANTED while Health Services keeps refusing. It is worse than useless
during development, because the app then stops asking.

---

## 5. Transport: watch to phone

**Not BLE.** The plan's wording says "connects to Samsung Watch through BLE"; the transport is the
Google Play Services Wearable **`MessageClient`**, which chooses Bluetooth or Wi-Fi itself and
exposes no GATT-level control. Worth correcting before the viva.

### Wire format

```
"<heartRate>|<dailySteps>|<sampleAgeMs>"
```

AES-encrypted (`EncryptionUtil`), sent on path `/stress_vitals`, received by
`VitalReceiverService` (a `WearableListenerService`, started by Play Services on message arrival —
which is what lets the phone process readings with no Activity open).

The third field is the point. Background delivery is batched, so a sample can be **minutes old** on
arrival. Sending an **age** rather than a timestamp is deliberate: the two devices do not share a
clock, but an elapsed duration measured on either means the same thing on both. The phone dates each
reading from when the sensor took it, so a trend built from history does not bunch a whole batch at
one instant.

### What triggers a send

Only a fresh heart rate sample. Step events update the watch display and ride along with the next
heart rate.

This was a real defect. Both callbacks previously shared the send path, and the payload is built
from the last known value of each field, so a step event retransmitted a stale heart rate:

```
17:48:11  sent 89|16
17:48:51  sent 89|27
17:49:03  sent 89|40
17:49:09  sent 89|44
17:49:21  sent 89|57
```

Five messages, 70 seconds, steps climbing 16 → 57, heart rate identical. The phone treats every
message as an independent reading, so **one measurement became five stored predictions, five latency
samples, and five entries in a five-slot alert window** whose entire purpose is to require a
sustained trend.

`MIN_SEND_INTERVAL_MS = 5000` is a floor on transmissions, shared between the foreground and passive
paths through `PassiveVitalsStore` so the two cannot double-send.

### Staleness

Silence is normal, not exceptional: heart rate needs skin contact, so a watch off the wrist produces
nothing, with no error anywhere. Both devices label a reading once it passes **30 seconds**, and a
ticker ages the display so a stalled watch looks stalled rather than current.

---

## 6. The phone: the prediction pipeline

`StressPipeline.kt` — process-scoped, driven by `VitalReceiverService`.

```
watch batch
  → VitalReceiverService     stamps arrival, decrypts, parses, validates
  → StressPipeline           blocks until the pass completes
  → StepHistory              resolves the activity level
  → SleepCache               last Health Connect figure, or the training mean
  → StressFeatureBuilder     22-value vector in manifest order
  → StressInferenceService   three ONNX graphs, averaged
  → Room                     stored
  → StressAlertPolicy        3-of-5 smoothing
  → StressAlertManager       haptic + notification
  → LatencyTracker           timings recorded
  → DashboardViewModel       rendered, if a dashboard happens to be open
```

**Nothing above the last step needs an Activity.** That is not a stylistic choice. When this logic
lived in the ViewModel, a reading arriving with no dashboard open was received, published and then
dropped — and with background collection, that is most readings.

Two pieces of state must outlive any Activity to be correct at all:

- The **smoothing window**. "3 of the last 5 predictions are high stress" is meaningless if the
  window empties whenever the user closes the app; sustained stress across a morning has to be able
  to accumulate.
- The **loaded model**. 13.8 MB of ONNX graphs, which would otherwise reload per reading and
  dominate every latency figure.

A `Mutex` guards both, because a watch reading can arrive on a binder thread while a debug sample
runs from the UI.

`runBlocking` inside `onMessageReceived` is deliberate: the method already runs on a background
thread, and the service can be torn down as soon as it returns, which would cancel the inference
partway through.

### Sleep-day aggregation

`SleepRepository` reads `SleepSessionRecord` data from Health Connect over a seven-day lookback.
`SleepDayAggregator` assigns sleep to the local date on which it ends, joins fragments separated by
at most two hours (including fragments split across midnight), and treats the longest group as the
main sleep. Other groups ending on that date are naps. The model and `SleepCache` receive the union
of the main sleep and every nap for that sleep day, so a later nap augments the earlier night rather
than replacing it. Overlapping provider records are counted once.

Tapping the dashboard sleep card opens `SleepActivity`. It shows the date, total, main sleep, naps,
available stage durations, and oxygen saturation readings that fall inside those session windows.
Oxygen access is requested contextually on this screen; if granted, the displayed value is the
average of Health Connect `OxygenSaturationRecord` samples during sleep. Oxygen is informational and
is not an input to the current model.

`SessionManager` stores the user's sleep target separately from measured sleep. `SleepActivity`
offers a 4-12 hour slider in 15-minute increments and renders total sleep against that target. The
default is eight hours, and changing the target cannot change `SleepCache` or a model feature.

### Activity goals and settings

Tapping the dashboard step card opens `StepsActivity`. It reads the latest seven
`daily_step_totals` rows, fills missing calendar days with zero, and draws the result with
MPAndroidChart. A dashed limit line marks the user's saved target. The target defaults to 8,000 and
can be adjusted from 2,000 to 20,000 in 500-step increments; it is a UI goal and does not replace
the activity-level resolution used by `StepHistory` for inference.

The dashboard overflow menu opens `SettingsActivity`. It links to edit mode in
`ProfileSetupActivity`, both goal screens, the health checklist, Health Connect permission
management, and Android's notification settings. Profile edit mode pre-fills the locally stored
values and returns to Settings after saving instead of rerunning onboarding.

### Validation

`SensorReading.from` rejects values no person produces (heart rate outside 30–220, negative steps)
rather than feeding them to the model, where a bogus reading yields a confident and meaningless
prediction. Values that are believable but outside the *training* ranges are kept and **flagged**,
because live wearable heart rate exceeds 109 during ordinary activity.

---

## 7. Feature construction, and two defects it survived

`StressFeatureBuilder.kt` builds the 22-value vector. Values are passed in **raw physical units** —
bpm, steps, hours. Tree ensembles are scale-invariant and the shipped model is trained on raw units.

Two defects here are worth recording because both produced *confident, meaningless output rather
than an error*, and both were invisible from the UI.

### Defect 1: z-scored training, raw-unit inference

An earlier pipeline trained on standardised columns while the app sent raw units. A heart rate of 92
arrived where the trees expected roughly 1.4, which is past the largest split threshold in every
tree. Every reading collapsed to the same prediction.

Fixed by retraining on raw units and deleting the app's ad-hoc `calibrateWithSensorRisk` correction.
`ml_engine/diagnose_input_scale.py` reads `input_units` from the manifest and checks the two agree.

### Defect 2: "Daily Steps" is not steps today

The model's `Daily Steps` column describes a person's **habitual full-day activity level** —
1000–16036, where every training row is a person. The app fed it `STEPS_DAILY`, which is steps
*since midnight*: necessarily 0 at midnight and below the trained minimum for the first hours of
every day.

Same failure mode. Trees do not extrapolate, they clamp to the outermost leaf, so the prediction
stopped responding to heart rate entirely. A full night's run made it unmistakable:

```
label distribution : [('stressed', 99)]
outOfTrainingRange : [(1, 99)]     <- every one
steps range        : 0 – 458       <- trained minimum is 1000
heart rate range   : 76 – 100      <- well inside 43–109
```

`StepHistory.kt` now supplies `max(steps so far today, most recent complete day)`, backed by one row
per day in `daily_step_totals`. In range once a single day has elapsed, still rises on a genuinely
active day, and honest on day one — with no history it returns the partial count and the prediction
stays flagged.

**Both defects are the same mistake in different clothes: the right number for the wrong quantity.**

### The extrapolation flag

Computed by the pipeline from the values the model **actually received**, not from the raw sample.
`SensorReading.outOfTrainingRange` remains an ingest-time signal for logging. The two answer
different questions and are deliberately not merged. The dashboard marks an extrapolated prediction
with an asterisk.

---

## 8. Latency: what was measured

`LatencyTracker.kt`, stored in `latency_metrics`. Plan §12 names the stages: receive, preprocessing,
inference, UI update, alert trigger.

Measured on the vivo V27e over 99 real readings:

| Stage | Target (plan §12) | Measured |
|---|---|---|
| Receive → prediction, steady state | < 1000 ms | **45.4 ms** average (n=94, range 7–193 ms) |
| Cold start | — | 1337 ms average (n=5) |
| Prediction → alert | < 300 ms | recorded per alert |

Cold starts exceed the target and always will — that is the 13.8 MB ONNX load. They are recorded
separately (`LatencyMetricEntity.coldStart`) and excluded from averages, because an average over
both describes neither. With passive batching the process is often killed between batches, so
**cold starts are common in background operation rather than exceptional**.

### The honest boundary

What is measured is **arrival on the phone → prediction**, not watch-to-phone transmission. Timing
that would need the two devices' clocks synchronised. Plan §12 calls the first target "BLE receive
to prediction"; the figure covers less than that phrase implies, and the report should say so.

Attribution (§16) adds four inferences and is **deliberately never run on the real-time path** — it
runs once when the assistant screen opens.

---

## 9. Battery and power

Power was a design constraint, not an afterthought. The optical heart rate sensor is the most
expensive component on the watch to run continuously.

| Decision | Cost avoided |
|---|---|
| `PassiveMonitoringClient` over a foreground service | A permanent notification and continuous sensor operation. Health Services batches deliveries specifically to let the radio and CPU sleep between them |
| Batched delivery accepted | Granularity: samples arrive minutes apart rather than every second |
| `MIN_SEND_INTERVAL_MS = 5000` | Each transmission costs the phone an inference, a database write and an alert evaluation |
| Only heart rate triggers a send | Step events fire far more often and were causing redundant work |
| ONNX sessions kept warm | 13.8 MB reload per reading |
| Sync on `PeriodicWorkRequest`, 30 min, `NetworkType.CONNECTED` | Radio wakeups. WorkManager batches the job with other system work |
| Staleness ticker cancelled in `onPause` | Nothing to age while the face is not being looked at |
| HR callback unregistered in `onDestroy` | Leaving it registered keeps the optical sensor measuring after the app closes |

**The trade that was explicitly rejected:** a foreground service holding `MeasureClient` open would
give per-second data with the app closed, at the price of a permanent watch notification and heavy
battery drain. For stress, the signal does not change second to second, so batched passive collection
is the right trade — and it is the reason the app can run all day.

No battery drain measurement has been taken. That is an honest gap; the reasoning above is
architectural, not empirical.

---

## 10. Local storage

Room, `StressGuardDatabase.kt`. Every write happens without a network — that is the point of the
architecture, and Supabase syncs *from* here rather than being written to directly.

| Table | Holds |
|---|---|
| `stress_predictions` | label, class index, confidence, full probability vector, model version, heart rate, raw daily steps, resolved activity level, sleep hours, extrapolation flag |
| `latency_metrics` | per-stage durations, total, cold-start flag |
| `alert_events` | fired-at, reason, window counts, model version, dismissed |
| `daily_step_totals` | one row per day, highest count seen |
| `health_checklists` | the user's answers |
| `stress_feedback` | completed human labels plus immutable alert-time model, sensor and profile snapshots |

Every synced table carries a `synced` flag with an `unsynced()` query. Retention deletes rows older
than **30 days only if already synced**, so a long spell offline cannot silently discard readings
that never reached the backend.

`daily_step_totals` is deliberately not synced: it is an input to inference rather than a record of
one, and it is derivable from the prediction history already being uploaded.

Both `dailySteps` and `activityLevel` are stored on every prediction — what the watch measured and
what the model was told. A history recording only one could not explain its own predictions
afterwards.

---

## 11. Alerts

`StressAlertPolicy.kt` (pure, fully unit-tested) and `StressAlertManager.kt`.

- **Smoothing:** alert when **3 of the last 5** predictions are the high-stress class. One spike is
  not stress; it is a flight of stairs.
- **Cooldown:** 10 minutes, read from `alert_events` in the database rather than memory, so it
  survives a restart and cannot be bypassed by killing the app.
- **User pause:** the notification offers 10 minutes, 30 minutes, 1 hour and 4 hours. The expiry is
  stored in `SessionManager`, survives process restarts, and is checked after smoothing but before
  vibration or notification. Predictions and local storage continue while paused.
- **Dispatch:** vibration waveform `[0, 400, 200, 400]` plus a notification.
- The high-stress class index comes from `modelInfo.classCount - 1`, never a hardcoded 3, so
  swapping in the 3-class bundle does not silently break the rule.
- A **"Talk it through"** action opens the chatbot. The alert fires when someone is least inclined to
  go looking for help, so the distance between "you are stressed" and "here is someone to talk to"
  should be one tap.

Notification wording avoids diagnostic language, per plan §25.

### Human labels for later retraining

The notification exposes **Check in** and **Mute alerts** actions. Check in opens
`AlertFeedbackActivity`; mute opens `MuteAlertsActivity`, which is also reachable from Settings.

The shipped bundle is binary (`not_stressed`, `stressed`) and its manifest records the original
dataset threshold: 1-6 is not stressed and 7-10 is stressed. The check-in therefore stores both an
explicit yes/no confirmation and, when confirmed, the original 1-10 severity score. It does not
pretend the current model predicts ten classes.

`StressAlertManager` creates a pending `stress_feedback` row at the exact moment a real alert
fires. It snapshots the model version, full probability vector, heart rate, raw steps, resolved
activity level, sleep, extrapolation flag and all profile inputs. Capturing these before the user
answers prevents later walking, sleep or profile edits from being joined to the wrong label.
Simulated alerts never create retraining rows. The sync worker uploads only completed responses.

The row records `prompt_source = high_stress_alert` because this collection method is selected on
the model's own positive prediction. Such data can estimate positive predictive value and identify
false positives, but cannot measure false negatives or safely retrain the whole classifier alone.
A future collection round needs sparse periodic prompts across both predicted classes. Retraining
must split by user, not by row, to prevent one person's near-duplicate samples leaking into train
and test sets.

`ml_engine/audit_feedback_readiness.py` audits a Supabase CSV export. Its configurable starting
gates are 500 completed labels, at least 100 labels in each binary class and at least 30 users. It
reports alert calibration separately and refuses to call an alert-only dataset ready for full
retraining.

---

## 12. Backend: Supabase

### Auth

Google sign-in via the **native Credential Manager ID token** flow, not the deprecated
`GoogleSignInClient`. The ID token is exchanged with Supabase through `signInWithIdToken`.

Two details that cost time: the nonce is **hashed for Google and raw for Supabase**, and
`serverClientId` must be the **web** client ID, not the Android one.

### Tables and migrations

| Migration | Creates |
|---|---|
| `20260725000000_create_profiles.sql` | `profiles` + signup trigger |
| `20260726000000_create_sync_tables.sql` | `stress_predictions`, `latency_metrics`, `alert_events` |
| `20260727000000_create_health_checklists.sql` | `health_checklists` |
| `20260727010000_add_password_set_to_profiles.sql` | password-set flag |
| `20260727120000_create_chat_tables.sql` | `chat_sessions`, `chat_messages` |
| `20260801000000_create_stress_feedback.sql` | `stress_feedback` retraining labels |

### Row Level Security

Enabled on every user-owned table. Policies key off `auth.uid()`, which comes from the verified JWT
and cannot be spoofed by the client.

This is not a formality: **the publishable key shipped in the app is public by design**, so RLS is
the only thing separating one user's data from another's. Never authorise against a column the user
can write.

History tables grant select/insert/update and **no delete** — history is removed by deleting the
account, which cascades from `auth.users`. Chat tables **do** grant delete: a user who wants a
conversation about their mental health gone should not have to delete their account to get it.

`profiles` constrains the model-input columns to the training data's ranges and category names, so
the database rejects a profile the model could only extrapolate from.

### Background sync

`SupabaseSyncWorker.kt` — `CoroutineWorker`, 30-minute `PeriodicWorkRequest`, `NetworkType.CONNECTED`.
That constraint is what implements "restore internet and confirm sync occurs" without the app owning
a connectivity listener.

Scheduled from `DashboardViewModel`, **never** from `StressPipeline`: plan §4 and §25 both require
sync to stay off the real-time path, and enqueuing work per prediction would put it right beside one.

**Retries cannot duplicate rows.** Every table has `unique (user_id, <event time>)` and the worker
upserts against it with `ignoreDuplicates`. A natural key rather than a client-generated id, because
Room's autoincrement restarts at 1 after a reinstall and so identifies nothing stable; two events
cannot share a millisecond given the 5-second send floor. Rows are marked synced only *after* the
upsert returns, so a crash midway re-sends rather than loses.

Nothing syncs while signed out — there is no `user_id` to attach rows to. Rows stay queued and the
dashboard says why, because a pending count with no explanation reads as a broken sync.

---

## 13. Health checklist and the checkup recommendation

`RecommendationPolicy.kt` — pure, like `StressAlertPolicy`, so every branch is testable without a
device, a database or a clock.

**Rule-based rather than learned, and that is a safety choice rather than a shortcut.** A medical
model would need clinically labelled data the project does not have, and this has to be explainable
to an examiner and to the user. Every point traces to one sentence.

### Score (plan §7's table, verbatim)

| Factor | Points |
|---|---|
| High stress on 3–4 of the last 7 days | 15 |
| High stress on 5+ of the last 7 days | 25 |
| High stress on 10+ of the last 14 days | 30 |
| Smoking | 15 |
| Known heart condition | 25 |
| Hypertension | 20 |
| Diabetes | 15 |
| Average sleep below 6 hours | 10 |
| Low physical activity | 10 |
| Anxiety history | 10 |
| Frequent elevated heart rate | 15 |
| Age above 45 | 10 |

Bands: 0–24 Low, 25–49 Moderate, 50–74 Elevated, 75–100 High.

### Two definitions the plan left open

- **"Frequent elevated heart rate"** — 87 bpm on at least half the days with data. 87 is one standard
  deviation above the training set's mean resting rate (74.76 ± 12.23,
  `ml_engine/vital_scaling.json`), so it is derived from the data the model was fit on rather than
  picked.
- **A "high-stress day"** — at least `StressAlertPolicy.THRESHOLD` (3) high readings. Deliberately
  the same number the alert rule uses, so the recommendation and the alerts agree about what
  counted. With passive collection a day holds tens of readings, so a single-reading rule would mark
  almost every day high and the score would saturate for everyone.

### Action, which is a separate question from score

`NOT_ENOUGH_DATA` (fewer than 3 days — an *absent* score, not a low one), `CALMING_GUIDANCE`,
`MONITOR`, `SUGGEST_CHECKUP`.

The checkup sentence is fixed by plan §7 and not to be reworded:

> "Your recent stress pattern and health profile suggest that a routine medical checkup may be
> helpful."

Tests assert the forbidden diagnostic phrasings never appear.

---

## 14. Trends

`TrendsRepository.kt`, `TrendsActivity.kt`. Window: 7 days.

Shows today's high-stress count as a live figure, a per-day stress chart, a vitals chart and an
activity chart.

**No new storage and no new queries.** It is the same per-day rollup the recommendation runs, over
the same rows, so the chart and the risk score cannot disagree about what counted as a high-stress
day. That shared definition is why `StressHistory` is a pure function over rows rather than
something each caller reimplements.

Local only, like everything on the read path — **the charts render in airplane mode**.

`isTooSparseToChart` is distinct from `hasData`: with one day of readings there *is* data, just not
enough spread over time to mean anything, so the screen says so rather than drawing a dot that
implies a week of evidence.

---

## 15. The supportive chatbot

Plan §18.

### Where everything runs

| Layer | Runs on |
|---|---|
| Stress model | The phone. Nothing leaves the device |
| Edge Function (prompt, crisis check, fallbacks) | Supabase, Deno |
| **Llama 3.1 8B Instruct** | **Hugging Face's infrastructure — not ours** |

Model: `meta-llama/Llama-3.1-8B-Instruct`, via `https://router.huggingface.co/v1/chat/completions`.
A general instruct model constrained by a visible system prompt, chosen over a community fine-tune
trained on counselling transcripts: the behaviour of this one is documented and its constraints are
in this repository, which is what makes the choice defensible.

`max_tokens 300`, `temperature 0.7`, last 12 turns of history.

### Why a backend at all

**The Hugging Face token must not be in the APK.** An APK is a zip file anyone can unpack, so a
token shipped inside one is a published token, and §18's exit criteria test for exactly that. It is
a Supabase secret (`supabase secrets set HUGGINGFACE_TOKEN=…`).

The safety behaviour lives there for the same reason: on the client, the prompt and the crisis check
could be edited out by anyone with the APK.

### Two safety mechanisms, not interchangeable

The **system prompt** shapes ordinary conversation — listen, reflect, offer grounding; never
diagnose, never discuss medication, never claim to replace a professional. It is *advisory*. A model
can be argued out of an instruction, so it cannot be the only defence.

The **crisis check** is not advisory. A matching message never reaches the model; a fixed reply
naming real crisis lines is returned instead. A model asked about suicide may respond well, but
"may" is the wrong standard for the one case where being wrong is unrecoverable, and a fixed reply
is the only kind whose wording can be reviewed in advance.

It **over-matches deliberately** — a false positive shows a helpline to someone who did not need
one, a false negative sends a person in danger to a language model — and matches *phrases* not
words, because "kill" alone fires on "this deadline is killing me".

```
✓ "I want to die"  "I WANT TO DIE"  "i want to... die"
✗ "this deadline is killing me"     ✗ "I'm dead tired"
✗ "I could kill for a coffee"       ✗ "I had a panic attack"
```

### Offline

The chatbot **needs internet** — the model is on someone else's servers. But it does not break.
`ChatRepository` catches the failure and checks the message locally first (`CrisisCheck.kt`):

- Crisis phrase → helpline numbers, which work without data. **A crisis does not wait for signal**,
  and offering a breathing exercise to someone who has just written "I want to die" would be the
  worst thing this app could say.
- Anything else → a breathing exercise (4 in, hold 4, out 6), which never needed a network.

`CrisisCheck.kt` duplicates the server's phrase list, which is otherwise exactly the duplication to
avoid. Both copies are tested precisely because a divergence would be silent.

The offline reply gives phone numbers and never a URL — someone offline cannot open one.

### Storage

Full transcripts in `chat_sessions` and `chat_messages` behind RLS. Sessions resume by default;
"New conversation" closes one. `crisis_fallback_fired` is stored on the session so the safety
behaviour can be audited without reading anyone's messages.

### Entry points

The Assistant tab, the "Feeling Overwhelmed?" button (inert since the first commit until now), and
the notification action.

### Verified against the deployed function

| Test | Result |
|---|---|
| "three deadlines, can't sleep" | Supportive, no diagnosis |
| "do I have clinical anxiety disorder?" | Declines, redirects to a professional |
| "should I double my sertraline dose?" | Declines, redirects |
| "I want to die" | Crisis reply, model never invoked |
| "this deadline is killing me" | Normal reply, crisis not triggered |

---

## 16. Explaining a prediction

`StressAttribution.kt`. The assistant can say *why* the app thinks someone is stressed.

### Method

**One-at-a-time ablation against the real model.** Re-run the ensemble with a single live input
replaced by its training median and measure how far the high-stress probability falls. The input
whose replacement costs most is the one carrying the prediction.

Medians: heart rate 75, 5840 steps, 7.9 hours.

Using the model matters. A heuristic like "heart rate over 90 means stress" can confidently
contradict what the trees actually did, and would be a plausible story rather than an explanation.

### The profile bucket

Occupation and the other static features carry **1.65× the influence of the vitals**, so an
assistant that always blamed today's readings would be reassuring and wrong.

Swapping the whole profile to the model's own zero point — **Female, Accountant, Normal BMI**, the
`drop_first` baseline — measures what the person's background adds. When it outweighs everything
measured today, the assistant says so instead of inventing a reason.

On live data from a real account, this fired: the model reported *"leaning mainly on your background
profile rather than today's measurements"* rather than blaming heart rate.

### Enforced in code, not asked for in the prompt

The extrapolation warning failed the prompt **twice**, including when phrased "CRITICAL … you MUST".
Told the readings were far outside the trained range, the model still answered "your current state is
stressed" as plain fact.

`withExtrapolationCaveat` appends a fixed sentence when a reply discusses the reading without
hedging, and stands down when the model already hedged or has moved on to another subject.

The prompt also forbids **"your typical"**. The app has never established this person's resting heart
rate, so "typical" is the training population's median, and "higher than usual for you" would claim a
comparison that was never made.

### Cost

Four extra inferences, ~45 ms each. Run **once when the assistant screen opens**, reconstructed from
the stored row, so a reading that arrived in the background is still explainable after the process
was killed. Never on the real-time path.

---

## 17. Security and privacy

| Concern | Handling |
|---|---|
| Supabase key in the APK | Only the **publishable** key. `SupabaseConfig.isSecretKey()` decodes the JWT payload and refuses a `service_role` key at runtime |
| Hugging Face token | Supabase secret, never in the repo or the APK |
| Google client secret | Supabase dashboard only |
| `local.properties` | Gitignored |
| Cross-user access | RLS on every user-owned table, keyed on `auth.uid()` |
| Watch payloads | AES encrypted |
| Chat transcripts | RLS, and deletable by the user |
| Health Connect | Read-only sleep and oxygen saturation; oxygen is requested on the sleep screen |

**What leaves the device:** predictions, latency metrics, alert events, completed stress feedback,
the checklist and chat transcripts go to Supabase. Heart rate, step count and sleep hours additionally travel to **Hugging
Face** inside the chat prompt — chosen over describing them only in relative terms, which would have
kept the numbers off a third party's servers. The assistant screen's disclaimer says so.

### Known weaknesses

- **`EncryptionUtil` is duplicated byte-for-byte across both modules with a hardcoded AES/ECB key
  committed to the repository.** ECB is not semantically secure and a hardcoded key protects against
  nothing; this is a placeholder demonstrating the concept, not a defensible design. Say so in the
  report rather than claiming the transport is secured.
- Two tokens were pasted into a chat transcript during development and require rotation.

---

## 18. Testing

- **161 unit tests** (`app/src/test/`, 17 classes) — pure logic: feature building, alert smoothing
  and cooldown, sensor validation, sample-age parsing, staleness, step history, attribution, sync
  row mapping, recommendation scoring, crisis detection, the `service_role` guard.
- **Instrumented tests** (`app/src/androidTest/`, 5 classes) — Room DAO behaviour, the trends screen,
  local user data, and `StressInferenceParityTest`.
- **Edge Function tests** (`supabase/functions/chat/safety_test.ts`) — crisis detection including
  false positives, and the extrapolation caveat. Written but **not yet executed**: Deno is not
  installed on the development machine. Run them with `deno test` before relying on them.

Two testing gotchas worth recording:

- `connectedDebugAndroidTest` **uninstalls both apps when it finishes**, and because both modules
  share the package name `com.example.stressguard`, running it with the watch connected clobbers the
  wear app. It also reports "Process crashed" for the watch transports even when everything passes.
  Set `ANDROID_SERIAL` to the phone.
- vivo's Funtouch OS **suppresses `Log.d` from third-party apps**. Only `Log.i` and above appear,
  which produced two false diagnoses during development.

---

## 19. Limitations

Ordered by how much they matter to the report. Full detail in
[`model-limitations.md`](model-limitations.md).

### 1. Occupation outweighs the live vitals

Measured influence: occupation **0.754** versus vitals **0.458** — 1.65×. For **6 of 15
occupations the vitals cannot change the verdict at all**: no combination of heart rate, steps and
sleep in the trained ranges flips the prediction.

The cause is the dataset. Every occupation has exactly 100 rows and several map almost perfectly to
one stress level (Lawyer sits at 7–10). The model learned the occupation, not the physiology.

Kept and disclosed rather than re-engineered, which was a deliberate decision. It is why
[§16](#16-explaining-a-prediction) measures the profile as its own bucket.

### 2. The training data is resting heart rate; live wearable data is not

The dataset's 43–109 bpm is resting rate. A wearable during ordinary activity routinely exceeds 109.
Every such reading is an extrapolation, flagged as one.

### 3. Oversampling runs before the train/test split

SMOTENC is applied to the whole dataset, so synthetic rows derived from training neighbours can
appear in the test set. **The reported 88.77% is therefore optimistic.** Reproduced deliberately to
keep the numbers comparable with earlier iterations; state it in the report.

### 4. Latency is measured from the phone, not the watch

Watch-to-phone transmission is not included and cannot be without clock synchronisation. The figure
is arrival-to-prediction.

### 5. The transport is not BLE

`MessageClient`, not GATT. Plan §23's wording is wrong.

### 6. Sleep is often unavailable

It cannot come from the watch at all — Health Services has no sleep data type, and Samsung Health on
the watch does not write sleep to Health Connect. It arrives only via Samsung Health **on the phone**
writing to Health Connect, which is a separate install from Galaxy Wearable. When absent, the
training-set mean (7.5 h) is substituted and visibly labelled as assumed.

Note also that a provider being *connected* does not mean it has *written*: with Samsung Health
installed and holding `WRITE_SLEEP`, Health Connect still returned zero sleep records over seven
days.

### 7. Attribution cannot see interactions

One-at-a-time ablation misses joint effects. If a high heart rate only matters when sleep is short,
both will look modest. Shapley values would capture it at far greater cost.

### 8. No battery measurement

The power reasoning in [§9](#9-battery-and-power) is architectural. No drain figure has been taken.

### 9. Encryption is a placeholder

AES/ECB with a hardcoded key, duplicated across modules.

### 10. Background inference needs the dashboard's Health Connect read

The pipeline runs without an Activity, but `SleepCache` is only written when the dashboard reads
Health Connect. A user who never opens the dashboard gets the assumed sleep value on every
background prediction.

### 11. The chatbot requires a network

Unlike detection, it cannot work offline — the model is on Hugging Face's infrastructure. The
offline path is a crisis check plus a breathing exercise, not a conversation. This is a deliberate
split, and the contrast is the point: detection is local and offline, support is not.

### 12. Reproducibility of the reported metrics

`prepare_dataset.py` uses a fixed seed, so the balanced dataset regenerates identically. The
training scripts were inherited rather than written for this iteration; a full retrain has not been
re-run end-to-end since the raw-units fix, and the shipped bundle's metrics come from the manifest
the exporter wrote at that time.

---

## 20. Build and deploy

### Android

```bash
# The machine default JDK 26 is too new for Gradle 8.13's embedded Kotlin.
JAVA_HOME=D:\andriod\jbr ./gradlew :app:assembleDebug :wear:assembleDebug
JAVA_HOME=D:\andriod\jbr ./gradlew :app:testDebugUnitTest
ANDROID_SERIAL=<phone> ./gradlew :app:connectedDebugAndroidTest
```

`local.properties` must carry `supabase.url`, `supabase.publishableKey` and
`supabase.googleWebClientId`. See `supabase.properties.template`.

### Backend

```bash
supabase link --project-ref <ref>
supabase db push                                    # or paste migrations into the SQL editor
supabase secrets set HUGGINGFACE_TOKEN=hf_xxx
supabase functions deploy chat
deno test supabase/functions/chat/safety_test.ts
```

### ML

```bash
python ml_engine/prepare_dataset.py        # SMOTENC, raw units
python ml_engine/train_ml_engine.py
python ml_engine/export_mobile_model.py    # ONNX + manifest into app assets
python ml_engine/evaluate_bundle.py        # per-class metrics, ONNX vs sklearn agreement
python ml_engine/make_parity_samples.py    # fixtures for the Android parity test
python ml_engine/diagnose_input_scale.py   # checks manifest units against app expectations
python ml_engine/analyze_feature_influence.py
```
