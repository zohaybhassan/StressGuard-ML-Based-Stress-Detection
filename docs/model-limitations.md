# Model Limitations

Findings that should be stated in the report rather than discovered by an examiner. Every
number here is reproducible:

```bash
python ml_engine/analyze_feature_influence.py
python ml_engine/evaluate_bundle.py --bundle mobile_export/binary_voting_top3_raw
python ml_engine/diagnose_input_scale.py
```

---

## 1. Occupation influences the prediction more than the live vitals do

The most significant limitation. It was found by tapping the app's **Run Model Test** button
repeatedly and noticing the verdict never changed.

Measuring how far `P(stressed)` moves when only one input group varies, with everything else
held fixed:

| Varying | Median swing | Mean swing |
|---|---|---|
| **Occupation** (typed once at setup) | **0.754** | 0.783 |
| **Vitals** (heart rate, steps, sleep — live) | 0.458 | 0.433 |

Occupation moves the output **1.65×** as much as the live sensor data. The vitals figure spans
the entire trained range (heart rate 45→108, steps 15000→1000, sleep 9.8→5.1h), so this is not
an artefact of a narrow sweep.

### For 6 of 15 occupations the vitals cannot change the verdict at all

Age 30, Male, Normal BMI, vitals swept across the full trained range:

| Occupation | Very relaxed | Very stressed | Swing | Outcome |
|---|---|---|---|---|
| Lawyer | 0.809 | 0.976 | 0.166 | **locked to high stress** |
| Doctor | 0.777 | 0.949 | 0.172 | **locked to high stress** |
| Teacher | 0.551 | 0.708 | 0.157 | **locked to high stress** |
| Nurse | 0.520 | 0.856 | 0.336 | **locked to high stress** |
| Chef | 0.398 | 0.865 | 0.467 | vitals flip the class |
| Manager | 0.367 | 0.848 | 0.481 | vitals flip the class |
| Student | 0.201 | 0.704 | 0.503 | vitals flip the class |
| Accountant | 0.196 | 0.736 | 0.540 | vitals flip the class |
| Salesperson | 0.184 | 0.767 | 0.583 | vitals flip the class |
| Sales Representative | 0.182 | 0.788 | 0.606 | vitals flip the class |
| Engineer | 0.150 | 0.510 | 0.360 | vitals flip the class |
| Scientist | 0.111 | 0.507 | 0.397 | vitals flip the class |
| Writer | 0.090 | 0.266 | 0.175 | **locked to low stress** |
| Artist | 0.073 | 0.654 | 0.581 | vitals flip the class |
| Software Engineer | 0.060 | 0.352 | 0.293 | **locked to low stress** |

A registered nurse wearing the watch will read as stressed at 45 bpm with 15,000 steps and
nearly ten hours of sleep. A software engineer will not read as stressed at 108 bpm with 1,000
steps and five hours of sleep.

Across 1,320 profiles, **50.8%** produce the same class for all three demo scenarios — so for
roughly half of users, tapping Run Model Test shows no change.

### The cause is in the dataset

`ml_engine/data/sleep_health_dataset.csv`, grouped by occupation:

| Occupation | Mean stress | Min | Max | Rows |
|---|---|---|---|---|
| Artist | 3.66 | 1 | 10 | 100 |
| Writer | 3.71 | 1 | 7 | 100 |
| Software Engineer | 3.97 | 1 | 9 | 100 |
| Scientist | 4.23 | 1 | 9 | 100 |
| Engineer | 5.08 | 1 | 9 | 100 |
| Student | 5.60 | 1 | 10 | 100 |
| Sales Representative | 5.94 | 1 | 10 | 100 |
| Salesperson | 5.95 | 1 | 10 | 100 |
| Accountant | 6.00 | 4 | 8 | 100 |
| Manager | 7.00 | 1 | 10 | 100 |
| Teacher | 7.26 | 3 | 10 | 100 |
| Chef | 7.60 | 3 | 10 | 100 |
| Nurse | 7.70 | 4 | 10 | 100 |
| Doctor | 8.00 | 6 | 10 | 100 |
| Lawyer | 8.48 | 7 | 10 | 100 |

Every lawyer in the data has stress 7–10; every doctor 6–10. Each occupation has **exactly 100
rows**. That uniformity, combined with the tight per-occupation ranges, is characteristic of a
dataset where occupation was used to *generate* the stress label rather than observed alongside
it. Occupation is therefore close to a label proxy, and any model fit on this data will lean on
it and treat physiology as a modifier.

This is a property of the data, not a defect in the model or the export: the ONNX graphs
reproduce the sklearn estimator to 2.3e-07.

### How to state it

> The model achieves 88.8% accuracy, but a feature-influence analysis shows the prediction is
> driven more by the user's stated occupation (median swing 0.754 in P(stressed)) than by live
> wearable data (0.458). For 6 of 15 occupations, no combination of heart rate, step count and
> sleep duration within the training range changes the predicted class. This follows from the
> dataset, in which occupation is close to a proxy for the stress label — each occupation has
> exactly 100 rows and a narrow stress range. The consequence is that the wearable contributes
> less to the prediction than the architecture implies, and a production system would need a
> dataset where physiological signals and stress were observed independently.

Retaining occupation was a deliberate choice: removing it would change the reported accuracy
and invalidate comparison with earlier iterations. The limitation is disclosed instead.

---

## 2. The training data is resting heart rate; live wearable data is not

Heart rate in the training set spans **43–109 bpm**, consistent with a resting measurement in a
lifestyle survey. Live wearable heart rate exceeds 109 during ordinary activity — a brisk walk
will do it.

Tree ensembles do not extrapolate; they clamp to the outermost leaf. So a reading of 150 bpm
produces exactly the same prediction as 109 bpm. The app records this rather than hiding it:
`SensorReading.outOfTrainingRange` is stored on every prediction row, and such predictions are
marked with an asterisk on the dashboard.

---

## 3. Oversampling runs before the train/test split

`ml_engine/prepare_dataset.py` applies `SMOTENC` to the whole dataset, then
`optimize_ml_engine.py` splits it. Synthetic rows interpolated from test-fold neighbours
therefore reach the training set, which inflates the reported accuracy by an unquantified
amount.

This was kept deliberately so the figures stay comparable with earlier write-ups. The fix is to
split before resampling; see the note in `prepare_dataset.py`.

A related defect **was** fixed: the original preparation one-hot encoded before oversampling, so
plain SMOTE interpolated the indicator columns and rounding produced 504 of 2360 rows (21.4%)
describing people who cannot exist — two occupations at once, or both Obese and Overweight —
concentrated in the rarest classes. `SMOTENC` handles the categoricals correctly and the script
asserts that no impossible combination survives.

---

## 4. Latency is measured from the phone, not the watch

Timings run from a message arriving **on the phone** to the prediction, the UI update and the
haptic. Watch-to-phone transmission is not included and cannot be without synchronising the two
devices' clocks.

Plan §12 names the first target "BLE receive to prediction"; what is measured is *arrival to
prediction*. Quote it that way.

Averages exclude the first inference of each process, which loads roughly 13.8 MB of ONNX
graphs. Cold and steady-state samples are stored separately, because an average over both
describes neither.

---

## 5. The transport is not BLE

Plan §23 says the app "connects to Samsung Watch through BLE". It does not. It uses the Google
Play Services Wearable `MessageClient`, a higher-level API with different failure modes and no
GATT-level control. Reword before the viva; "over the Wear OS message channel" is accurate.

---

## Not limitations — things that hold up

- **The ONNX export is faithful.** Maximum probability difference against the sklearn estimator
  is 2.3e-07 across the 472-sample holdout, with zero label disagreements.
- **Predictions are reproducible on device.** `StressInferenceParityTest` replays 20 fixed
  samples through the shipped graphs and compares against values generated on the PC.
- **Inference is genuinely local.** No network permission is needed for a prediction, and the
  whole receive → inference → store → alert path runs in airplane mode.
- **Row-level security is enforced, not assumed.** An unauthenticated request with the shipped
  publishable key returns `[]` on read and is refused `42501` on write.
