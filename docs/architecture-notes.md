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
- Google sign-in screen (Supabase auth via Credential Manager)
- Profile setup form
- Health checklist (skippable; also reachable from the dashboard to edit)
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
   training-set-mean default is substituted if there is none, and labelled as assumed. Steps come
   from `StepHistory`, not straight off the watch — see below.
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

## "Daily Steps" means an activity level, not a running count

The model's `Daily Steps` column describes a person's **habitual full-day activity**: 1000 to 16036
in `ml_engine/data/sleep_health_dataset.csv`, where every row is a person rather than a moment. The
watch reports `STEPS_DAILY`, which is steps *since midnight* — a different quantity that happens to
share a name, and one that is 0 at midnight and below the trained minimum for hours every morning.

Feeding the partial count straight in put the input outside the trained range. Tree ensembles do
not extrapolate; they clamp to the outermost leaf, so the prediction stopped responding to heart
rate entirely. A full night's run made the failure unmistakable:

```
label distribution : [('stressed', 99)]
outOfTrainingRange : [(1, 99)]     <- every one
steps range        : 0 – 458       <- trained minimum is 1000
heart rate range   : 76 – 100      <- well inside 43–109
```

This is the same class of defect as the z-scored training data fixed earlier: the right number for
the wrong quantity, producing confident and meaningless output rather than an error.

`StepHistory` supplies `max(steps so far today, most recent complete day)`, backed by one row per
day in `daily_step_totals`. In range once a single day has elapsed, still rises on a genuinely
active day, and honest on day one — with no history it returns the partial count and the prediction
stays flagged as an extrapolation, because the user's activity level genuinely is not known yet.

The day total keeps the **highest** count seen, not the latest, because a reading landing just after
the watch's midnight reset would otherwise wipe the day.

Both figures are stored on every prediction: `dailySteps` is what the watch measured, `activityLevel`
is what the model was told. A history that recorded only one of them could not explain its own
predictions afterwards.

**The extrapolation flag follows the model's inputs, not the raw sample.** `StressPipeline` computes
it from the values actually handed over; `SensorReading.outOfTrainingRange` remains an ingest-time
signal for logging. The two answer different questions and are deliberately not merged.

## Sleep does not come from the watch, and cannot

Heart rate and steps arrive over the watch link. Sleep does not, and no amount of work on that
link would change it — the constraint sits below us at three separate layers, verified on a
Galaxy Watch 4 running Wear OS 6 (API 36):

| Layer | Finding |
|---|---|
| Watch — Health Services | **No sleep data type exists.** `getCapabilities()` returns eleven types: heart rate, steps, distance, calories, floors, elevation and their daily variants. Sleep is not among them |
| Watch — Samsung Health | Tracks sleep in its own private store. Its manifest declares `READ_HEART_RATE`, `READ_OXYGEN_SATURATION`, `READ_SKIN_TEMPERATURE` — and **no `WRITE_SLEEP`** |
| Watch — Health Connect | Present as a platform service (`healthconnect: IHealthConnectService`), but nothing writes sleep into it, so reading it on-watch returns nothing |

Sleep therefore reaches the model the only way it can: **Samsung Health on the phone** receives it
from the watch and writes it to **Health Connect**, which `HomeDashboardActivity` reads as
`SleepSessionRecord` over the last 24 hours.

That chain has two links that are easy to miss, and both were hit in turn.

**Galaxy Wearable is not Samsung Health.** Galaxy Wearable pairs the watch but writes no health
data; Samsung Health does, and it is a separate install. With Galaxy Wearable present and Samsung
Health absent, the app reads a real, empty Health Connect and substitutes the training-set mean.

**Connected is not the same as writing.** With Samsung Health installed, listed under Health
Connect's "Your health apps", and holding `WRITE_SLEEP` with `USER_SET`, Health Connect still
returned zero sleep records over seven days while Samsung Health's own screen showed a full night.
Health Connect's "Recent access" listed the app's reads and no Samsung Health activity at all. A
provider shares data on its own schedule and generally only from the moment it was connected, so
nights recorded before the link was made may never appear. The permission grant proves consent, not
delivery — worth stating plainly, because every layer reports success while the value stays fake.

Three consequences shaped the code:

- **The substitution is labelled with its reason.** `sleepDetail` travels in `DashboardUiState` and
  renders as `Sleep: 7.5 hrs (assumed — no sleep records)`. It was previously written straight to
  `tvConnectionState`, which `renderPrediction` overwrites on the next emission, so nobody ever
  saw it.
- **Sleep is re-read on every resume**, not once in `onCreate`. Otherwise installing a provider has
  no effect until the app is killed and relaunched.
- **The watch shows no sleep line.** It used to display a hardcoded `"7.2 hrs"` that was never
  measured and never transmitted — a fabricated number presented as a reading.

## Backend sync (plan §15)

`SupabaseSyncWorker` drains the local queues into `stress_predictions`, `latency_metrics`,
`alert_events` and `health_checklists`. It runs on WorkManager every 30 minutes with a
`NetworkType.CONNECTED` constraint —
that constraint is what implements "restore internet and confirm sync occurs" without the app owning
a connectivity listener.

Scheduled from `DashboardViewModel`, never from `StressPipeline`. Plan §4 and §25 both require sync
to stay off the real-time path, and enqueuing work per prediction would put it right beside one.

**Retries cannot duplicate rows.** Every table has `unique (user_id, <event time>)` and the worker
upserts against it with `ignoreDuplicates`. A natural key rather than a client-generated id because
Room's autoincrement restarts at 1 after a reinstall and so identifies nothing stable; two events
cannot share a millisecond given the watch's five-second send floor. Rows are marked synced only
after the upsert returns, so a crash midway re-sends rather than loses.

Nothing syncs while signed out — there is no `user_id` to attach rows to. They stay queued rather
than being discarded, and the dashboard says why: a pending count with no explanation reads as a
broken sync.

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

Every table carries a `synced` flag with an `unsynced()` query, which `SupabaseSyncWorker` drains.
Retention deletes rows older than 30 days **only if already synced**, so a long spell offline
cannot silently discard readings that never reached the backend.

`daily_step_totals` is the one table that is not synced. It is an input to inference rather than a
record of one, and it is derivable from the prediction history already being uploaded.

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

## The checkup recommendation (plan §7, §16)

`RecommendationPolicy` is pure and rule-based, deliberately, and `StressAlertPolicy` is the model
it follows: the decision is a function of its inputs, so every branch is tested without a device.
A learned model was rejected for the reason plan §7 gives — there is no clinically labelled data —
and for one it does not: a score that recommends seeing a doctor has to be explainable to the
person reading it, so the card lists each factor and the points it contributed.

The pipeline is `stress_predictions` → `StressHistory.summarise` → per-day rollups →
`RecommendationPolicy.evaluate`.

Three decisions worth stating in the report:

- **The stress tiers are a ladder, not a sum.** Plan §7 lists "3-4 days of 7: +15", "5+ days of 7:
  +25" and "10+ days of 14: +30" as separate lines, but 10 high-stress days in 14 almost always
  also means 5 in 7. Adding them would score one underlying fact three times and put a merely
  stressed user at 70 before any health factor was considered, so the highest tier reached wins.

- **A high score is not the same as a checkup.** The action is driven by the stress pattern and the
  four major conditions, not by the score. Age, smoking and inactivity total 35 with no stress at
  all, and telling someone to see a doctor on that basis — when the app has observed nothing — is
  not what the app is for.

- **A day needs `StressAlertPolicy.THRESHOLD` high readings to count as a high-stress day.** With
  passive collection a day holds tens of readings, so a single-reading rule would mark nearly every
  day high and the score would saturate for everyone. Reusing the alert's own threshold also means
  the alerts and the recommendation agree about what counted.

`sleepDisorder` and `highCaffeineUse` are collected — plan §6 lists both — but score nothing,
because §7's table gives them no weight and inventing one would put an unsourced number into a
medical-adjacent score.

There is deliberately **no `daily_stress_summaries` table**, despite plan §6 listing one. Local
retention keeps 30 days and every window the score asks about is 14 days or less, so the source
rows are always present; a stored rollup would duplicate them and add a staleness bug for nothing.
Every prediction is already uploaded, so server-side rollups remain possible without the app
maintaining a second copy.

## Local migrations, and why the destructive fallback had to go

`StressGuardDatabase` ran `fallbackToDestructiveMigration()` through versions 1 and 2. That was the
right trade while history was a display: a developer reinstalling should not hit a crash, and
nothing depended on old rows.

It stopped being the right trade when the history became an **input**. The risk score counts
high-stress days over one or two weeks, so a wipe on a version bump does not merely clear a chart —
it silently resets the recommendation to "not enough data" and takes a fortnight to recover, at the
moment the schema changed and nobody is looking. The same rows are the report's evidence.

Version 3 therefore ships real migrations and no fallback. A missing migration now fails loudly in
development rather than degrading to a silent wipe on a user's device.

Two things make this safe rather than merely intended:

- **The DDL is Room's own.** `MIGRATION_2_3` uses the `createSql` from
  `app/schemas/…/3.json` verbatim, backticks included. Hand-written equivalents are where
  migrations go wrong: a stray `DEFAULT 0` makes `TableInfo.read()` disagree with the expected
  schema and Room throws on first launch after the update. Room records no default for a column
  whose default is a Kotlin one, which is exactly the mismatch that was avoided here.
- **`exportSchema` is now true and the JSON is committed**, so the schema is diffable and Room
  fails the build when an entity changes without a version bump.

`StressGuardMigrationTest` covers 1→3 and 2→3 on a real device, and asserts the *rows survive*
rather than only that the migration ran — a migration that dropped and recreated the table would
satisfy Room's schema validation and still lose the user's history. It does not use
`MigrationTestHelper`: that builds the starting database from exported JSON, and versions 1 and 2
shipped with `exportSchema = false`, so no JSON for them exists. The tests create the old database
with the DDL those versions shipped and then open it *through Room*, which is the path a real
device takes and makes Room's own validation the assertion.

## The trends screen (plan §17)

`TrendsRepository` reads the same rows through the same `StressHistory.summarise` the recommendation
uses, so the chart and the risk score cannot disagree about what counted as a high-stress day. That
shared definition is why `StressHistory` is a pure function over rows rather than something each
caller reimplements — two independent rollups is how a dashboard ends up contradicting its own
advice.

Three charts, because the quantities do not share a scale. Heart rate sits near 70 and sleep near
7.5, so they share one chart on two axes; activity is in the thousands and gets its own. The stress
chart draws a dashed limit line at `StressAlertPolicy.THRESHOLD` and colours each bar by whether it
cleared it, so a user can see *why* a day did or did not count rather than being handed the verdict.

**Sparse data is a first-class state, not an error.** With passive collection the watch is not worn
every day, so `StressTrends` separates "no readings at all" from "one day so far" and the screen
says something different for each. A single-day chart is a dot that implies a week of evidence.

`daysWithData` is quoted in the headline for the same reason: three high-stress days across three
days of readings and across seven are very different facts, and the chart alone cannot show which.

MPAndroidChart is published only to JitPack, so `settings.gradle.kts` adds that repository scoped to
`com.github.PhilJay` alone — opened globally, a typo in any other coordinate could silently resolve
to someone's fork.

The `nav_assistant` tab was **removed** rather than left in place. It had been declared and wired to
nothing since the first dashboard layout; once the bottom bar actually navigated, a tab that did
nothing when tapped would read as a broken app rather than an unfinished one. It comes back with the
chatbot screen.

## The supportive chatbot (plan §18)

Every reply comes from a **Supabase Edge Function**, never from Hugging Face directly. That is not
a layering preference. An APK is a zip file anyone can unpack, so a token shipped inside one is a
published token, and §18's exit criteria test for exactly that. `supabase secrets set
HUGGINGFACE_TOKEN=…` puts it somewhere the app never sees.

The safety behaviour lives there for the same reason: on the client, the system prompt and the
crisis check could be edited out by anyone with the APK, and the app's only guarantee about what
the assistant says would be a guarantee about its own good intentions.

**Two mechanisms, and they are not interchangeable.**

The **system prompt** shapes ordinary conversation — listen, reflect, offer grounding; never
diagnose, never discuss medication, never claim to replace a professional. It is advisory. A model
can be argued out of an instruction, so it cannot be the only defence.

The **crisis check** is not advisory. A message matching it never reaches the model; a fixed reply
naming real crisis lines is returned instead. A model asked about suicide may respond well, but
"may" is the wrong standard for the one case where being wrong is unrecoverable, and a fixed
response is the only kind whose wording can be reviewed in advance and quoted in a report.

The check deliberately **over-matches**: a false positive shows a helpline to someone who did not
need one, a false negative sends a person in danger to a language model. It matches phrases rather
than words, because "kill" alone fires on "this deadline is killing me" — the ordinary stressed
speech the assistant exists to discuss.

**The check is duplicated in Kotlin** (`data/CrisisCheck.kt`), which is otherwise exactly the kind
of duplication to avoid. It closes one gap: with no network the repository falls back to a
breathing exercise, and offering that to someone who has just written "I want to die" is the worst
thing this app could say. A crisis does not wait for signal. The offline reply gives phone numbers,
never a URL, because someone offline cannot open one. Both copies are tested —
`supabase/functions/chat/safety_test.ts` and `CrisisCheckTest` — because a divergence between them
would otherwise be silent.

A general instruct model constrained by a visible prompt was chosen over a community fine-tune
trained on counselling transcripts: the behaviour of this one is documented and the constraints on
it are in this repository, which is what makes the choice defensible in a viva.

Transcripts are stored in `chat_sessions` and `chat_messages` behind RLS, and unlike the history
tables these grant `delete` — a user who wants a conversation about their mental health gone should
not have to delete their account to get it. Storage failures are logged and swallowed: losing the
archive is bad, dropping a conversation someone is mid-way through is worse.

Three entry points, per §18's "quick action from high-stress alert": the Assistant tab, the
"Feeling Overwhelmed?" button that had been inert since the first commit, and a **"Talk it through"
action on the high-stress notification**. The last matters most — the alert fires when someone is
least inclined to go looking for help, so the distance between "you are stressed" and "here is
someone to talk to" should be one tap.

### Telling the assistant *why* someone is stressed

The assistant is given the current reading and, more usefully, which input produced it.

`StressAttribution` answers "why" by **asking the model rather than guessing**: it re-runs the real
ensemble with one live input replaced by its training median and measures how far the high-stress
probability falls. Whichever swap costs most is the input carrying the prediction. Medians come from
the file the model was fit on — heart rate 75, 5840 steps, 7.9 hours.

Using the model matters. A heuristic like "heart rate over 90 means stress" can confidently
contradict what the trees actually did, and would be a plausible story rather than an explanation.

**Its limitation, stated plainly:** one-at-a-time ablation cannot see interactions. If a high heart
rate only matters when sleep is short, that joint effect is invisible and both will look modest.
Shapley values would capture it at far greater cost; for naming a leading driver to a person this is
enough, provided it is honest about being a single-factor answer.

**The profile is measured as its own bucket.** Occupation and the other static features carry 1.65×
the influence of the vitals, so an assistant that always blamed today's readings would be reassuring
and wrong. Swapping the whole profile to the model's own zero point — Female, Accountant, Normal BMI,
the `drop_first` baseline — measures what the person's background adds. When it outweighs everything
measured today, the assistant says so instead of inventing a reason.

Attribution costs four extra inferences and is **deliberately never run on the real-time path**. It
happens once when the assistant screen opens, reconstructed from the stored row, so a reading that
arrived in the background is still explainable after the process was killed.

**Two claims are enforced in code rather than asked for in the prompt**, because prompt-only
enforcement was tested and failed:

- The crisis reply, as above.
- **The extrapolation caveat.** Told the readings were far outside the trained range and instructed
  in capitals to say so, the model twice answered "your current state is stressed" as plain fact.
  `withExtrapolationCaveat` appends a fixed sentence when the reply discusses the reading without
  hedging, and stands down when the model already hedged or has moved on to another subject. An
  extrapolation delivered confidently is worse than no reading, because nothing tells the user it
  was guesswork.

One wording rule earns its place: the prompt forbids "your typical". The app has never established
this person's resting heart rate, so "typical" is the training population's median, and "higher than
usual for you" would claim a comparison that was never made.

**Privacy note for the report:** heart rate, step count and sleep hours now travel to Hugging Face
inside the prompt. That was chosen over describing them only in relative terms, which would have kept
the numbers off a third party's servers. The assistant screen's disclaimer says so.

## Planned Next Layers

- Supportive chatbot backend, and the `nav_assistant` tab that goes with it

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
- **Local storage is not keyed by user.** The Room tables, the profile and the caches describe "the
  person using this phone", which was true only while there was no way to sign out. Two things now
  stand in for a `user_id` column: `LocalUserData.clear` on sign-out, and an identity check in
  `PostAuthRouter` that wipes local data when the signed-in account differs from the one it belongs
  to. The second exists because sign-out is not the only way a session ends — the Supabase client
  drops one on its own when a refresh token is revoked or replayed, which this app's logs have shown
  happening, and no sign-out path runs in that case. Adding `user_id` to the local tables would make
  the whole class of defect impossible rather than guarded against; it has not been done yet.
- `btnEmergency` is still declared in `activity_home_dashboard.xml` with no listener
- Instrumented tests must be pointed at the phone (`ANDROID_SERIAL`). Gradle installs `:app` on
  every connected device including the paired watch, where an Activity test cannot resume
- Command-line Gradle builds require JDK 21; the machine's default JDK 26 is too new for
  Gradle 8.13's embedded Kotlin and fails with `IllegalArgumentException: 26.0.1`. Android
  Studio works because it uses its own bundled JDK at `D:\andriod\jbr`.
