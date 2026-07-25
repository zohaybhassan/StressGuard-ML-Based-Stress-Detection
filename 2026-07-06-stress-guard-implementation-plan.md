# Stress Guard Implementation Plan

Date: 2026-07-06

Project: Stress Guard

Platform: Android app in Kotlin connected to Samsung Watch through BLE

Primary goal: Detect stress in near real time, alert the user when stress is high, store history, recommend checkups when long-term stress overlaps with user health risk factors, and provide a supportive chatbot.

## 1. Project Summary

Stress Guard is a hybrid Android and backend system. The Android app receives real-time wearable data from a Samsung Watch through BLE, runs stress inference locally using exported ONNX models, and triggers haptic alerts when stress stays high. Supabase stores user accounts, health checklist data, stress history, daily summaries, medical recommendation records, and chatbot metadata.

The architecture is intentionally hybrid because latency is a required concern. Real-time prediction and haptic alerts must not depend on a backend network call. Backend services are used for persistence, long-term analysis, recommendations, and optional chatbot/model services.

## 2. Current Project State

Known completed work:

- BLE pipeline already transfers watch data to the Android app.
- App currently displays real-time step count, heart rate, and sleep count.
- Login page exists.
- Dummy analytics dashboard exists.
- Stress ML model has already been trained.

Known ML assets:

- Primary training format: scikit-learn model saved with joblib.
- Tuned model path example: `ml_engine/artifacts_tuned_binary/best_tuned_model.joblib`.
- Baseline model path example: `ml_engine/artifacts/best_model.joblib`.
- Model type: `VotingClassifier` over RandomForest, XGBoost, and CatBoost.
- Mobile export format: ONNX bundles under `ml_engine/mobile_export/`.
- Binary stress ONNX bundle accuracy: about 89.4%.
- 3-class ONNX bundle accuracy: about 81.4%.
- Mobile bundle includes `random_forest.onnx`, `xgboost.onnx`, `catboost.onnx`, and manifest JSON.

Recommended MVP model:

- Use the binary stress ONNX bundle first.
- Output either `normal` or `high_stress`.
- Add 3-class support later only if the MVP is stable.

## 3. Target Architecture

```text
Samsung Watch
  -> BLE pipeline
  -> Android app in Kotlin
  -> local feature preprocessing
  -> ONNX stress inference on device
  -> haptic alert and dashboard update
  -> local Room database
  -> background Supabase sync
  -> Supabase Auth and Postgres
  -> weekly medical recommendation
  -> chatbot service
```

Critical real-time path:

```text
BLE receive -> preprocessing -> local ONNX inference -> UI update -> haptic alert
```

Non-critical backend path:

```text
Local history -> background sync -> Supabase -> weekly summaries -> recommendation records
```

The critical path should work even if internet is unavailable.

## 4. Main Engineering Principles

- Keep real-time stress inference on the Android device.
- Do not send every BLE packet to Supabase.
- Store raw or near-raw data locally when needed, then sync summaries.
- Upload stress prediction events, daily summaries, high-stress intervals, and latency metrics.
- Keep medical recommendation explainable and rule-based for v1.
- Avoid medical diagnosis claims.
- Keep Hugging Face chatbot credentials on the backend, not in the Android app.
- Measure latency explicitly and show it in the final report/demo.

## 5. Recommended Module Boundaries

Android modules/classes:

- `AuthManager`: handles sign-in, sign-up, sign-out, and session state.
- `BleManager`: handles Samsung Watch BLE connection and reconnection.
- `SensorDataParser`: converts BLE packets into app sensor models.
- `SensorRepository`: stores latest sensor data and exposes streams to UI.
- `FeatureWindowBuilder`: builds model-ready feature windows.
- `StressInferenceService`: loads ONNX models and produces stress predictions.
- `StressVotingService`: combines RF, XGBoost, and CatBoost ONNX outputs.
- `StressAlertManager`: handles thresholding, cooldown, notification, and haptic alert.
- `LatencyTracker`: records timings for BLE, preprocessing, inference, and alert.
- `LocalStressStore`: Room database access for offline storage.
- `SupabaseSyncWorker`: syncs local summaries and prediction events.
- `RecommendationRepository`: fetches and displays medical recommendations.
- `ChatbotRepository`: sends messages to backend chatbot endpoint.

Backend/Supabase modules:

- Supabase Auth for user accounts.
- Supabase Postgres for app data.
- Row Level Security for user-owned rows.
- Optional Supabase Edge Function or Python/FastAPI service for chatbot.
- Optional backend worker for weekly recommendation calculation.

## 6. Database Plan

Use Supabase tables with RLS enabled on all user-owned data.

Recommended tables:

```text
profiles
health_checklists
sensor_snapshots
stress_predictions
daily_stress_summaries
medical_recommendations
chat_sessions
chat_messages
model_versions
latency_metrics
```

Table purpose:

```text
profiles
  Stores app-level user profile information linked to Supabase Auth.

health_checklists
  Stores user-reported conditions and habits such as smoking, heart condition,
  hypertension, diabetes, sleep disorder, anxiety history, caffeine use, and
  physical inactivity.

sensor_snapshots
  Stores occasional summarized sensor values, not every BLE packet.

stress_predictions
  Stores prediction result, confidence, model version, and timestamp.

daily_stress_summaries
  Stores daily high-stress count, estimated high-stress minutes, average heart
  rate, sleep hours, and activity summary.

medical_recommendations
  Stores rule-based risk score, risk level, explanation, and recommendation text.

chat_sessions
  Stores chatbot session metadata.

chat_messages
  Stores chatbot messages only if required for the FYP demo. If privacy is a
  concern, store session metadata only.

model_versions
  Stores model name, version, accuracy, input feature list, and active status.

latency_metrics
  Stores measured local latency values for reporting and evaluation.
```

RLS rule pattern:

```text
Authenticated users can select, insert, update, and delete only their own rows.
Never expose service-role keys in the Android app.
Do not use user-editable metadata for authorization decisions.
```

## 7. Medical Recommendation Strategy

Use a rule-based risk score for v1 instead of training a separate medical model.

Reason:

- A medical ML model needs clinically valid labeled data.
- A rule-based score is easier to explain to examiners.
- The app should recommend a checkup, not diagnose disease.
- Rule-based logic is safer and more transparent.

Recommended score range:

```text
0-24: Low
25-49: Moderate
50-74: Elevated
75-100: High
```

Example scoring:

```text
High stress on 3-4 days in last 7 days: +15
High stress on 5+ days in last 7 days: +25
High stress for 10+ days in last 14 days: +30
Smoking: +15
Known heart condition: +25
Hypertension: +20
Diabetes: +15
Average sleep below 6 hours: +10
Low physical activity: +10
Anxiety history: +10
Frequent elevated heart rate: +15
Age above 45: +10
```

Recommendation rule examples:

```text
If high stress occurs for 5+ days in 7 days and the user has smoking,
heart condition, hypertension, or diabetes, recommend a routine medical checkup.

If high stress continues for 10+ days in 14 days, recommend a checkup even if
the checklist risk factors are limited.

If high stress occurs for only 1 day, show calming guidance only.
```

Required wording:

```text
Use: "Your recent stress pattern and health profile suggest that a routine
medical checkup may be helpful."

Avoid: "You have a disease" or "The app diagnosed a condition."
```

## 8. Phase 0: Project Organization and Baseline Audit

Objective:

Create a clean project baseline so future work is traceable and easy for an agent to continue.

Implementation tasks:

- Confirm Android project location.
- Confirm Kotlin setup and minimum Android SDK.
- Confirm existing BLE code structure.
- Confirm model export folder and ONNX files.
- Confirm whether app uses XML views or Jetpack Compose.
- Create a project README if missing.
- Add a `docs/` folder for architecture, model notes, and testing results.
- Add `.env.example` or equivalent documentation for backend configuration.

Deliverables:

- Updated README with setup steps.
- Architecture notes document.
- Confirmed list of existing screens and modules.
- Confirmed list of model artifacts.
- Confirmed build command for Android project.

Testing:

- Build the existing Android app.
- Launch app on emulator or physical device.
- Confirm login screen opens.
- Confirm dummy dashboard opens.
- Confirm BLE watch data still displays.

Exit criteria:

- A new agent can run the app and identify where BLE, dashboard, and login code live.

## 9. Phase 1: BLE Pipeline Stabilization

Objective:

Turn the current BLE display pipeline into a reliable sensor data pipeline.

Implementation tasks:

- Refactor BLE logic into `BleManager` if not already isolated.
- Add clear connection states: disconnected, scanning, connecting, connected, reconnecting, error.
- Add reconnection handling.
- Add permissions handling for Bluetooth and location permissions as required by Android version.
- Parse heart rate, step count, and sleep values into typed data models.
- Add validation for impossible values.
- Expose sensor data through a repository or state stream.
- Add timestamps to every received reading.

Deliverables:

- Stable BLE connection manager.
- Typed sensor data model.
- Live dashboard connected to real BLE state.
- User-visible connection status.
- Basic reconnect behavior.

Testing:

- Connect to Samsung Watch.
- Disconnect watch and confirm app shows disconnected state.
- Reconnect watch and confirm data resumes.
- Confirm app does not crash when BLE data is missing.
- Confirm invalid heart rate, step count, or sleep values are ignored or flagged.

Exit criteria:

- App reliably displays real-time heart rate, steps, and sleep values for at least 10 continuous minutes.

## 10. Phase 2: Local Storage and Offline Buffer

Objective:

Add local persistence so sensor readings and predictions are not lost when the network is unavailable.

Implementation tasks:

- Add Room database.
- Create local entities for sensor snapshots, stress predictions, daily summaries, and latency metrics.
- Add DAO methods for insert, query latest, query unsynced, and mark synced.
- Store only useful sensor snapshots, not every raw BLE packet.
- Add cleanup policy for old local data.

Deliverables:

- Room database schema.
- Local storage repository.
- Offline queue for future Supabase sync.
- Basic local history query.

Testing:

- Turn off internet and collect data.
- Restart app and confirm local data still exists.
- Confirm unsynced records are marked correctly.
- Confirm old data cleanup does not delete recent records.

Exit criteria:

- The app can collect and retain stress-related data offline.

## 11. Phase 3: ONNX Stress Inference on Android

Objective:

Run the binary stress model locally on the Android device.

Implementation tasks:

- Add ONNX Runtime Mobile dependency.
- Copy ONNX model files into Android assets.
- Load `random_forest.onnx`, `xgboost.onnx`, and `catboost.onnx`.
- Parse model manifest JSON.
- Implement feature ordering exactly as defined by the manifest.
- Implement feature scaling or normalization exactly as used during training.
- Build `StressInferenceService`.
- Build `StressVotingService`.
- Return stress class, confidence if available, and model version.
- Compare Android predictions against Python predictions using fixed test samples.

Deliverables:

- ONNX models bundled into Android app.
- Local stress inference service.
- Majority voting across the three ONNX models.
- Model validation test cases.
- Stress result displayed in dashboard.

Testing:

- Unit test feature ordering.
- Unit test preprocessing output.
- Unit test majority voting.
- Run fixed sample inputs through Python and Android.
- Confirm Android and Python predictions match for test samples.
- Measure ONNX inference time on a real Android device.

Exit criteria:

- Android app predicts `normal` or `high_stress` locally without backend access.

## 12. Phase 4: Latency Measurement and Optimization

Objective:

Prove the system handles real-time stress detection with low latency.

Implementation tasks:

- Add `LatencyTracker`.
- Record timestamps for BLE receive, preprocessing start/end, inference start/end, UI update, and alert trigger.
- Store latency metrics locally.
- Add debug screen or dashboard section showing average and latest latency.
- Identify slow steps and optimize.
- Ensure Supabase sync is outside the real-time alert path.

Target latency goals:

```text
BLE receive to prediction: under 1 second
Prediction to haptic alert: under 300 ms
Total local alert path: under 1.5 seconds
Backend sync: not part of alert latency
```

Deliverables:

- Latency tracking implementation.
- Latency metrics table in local storage.
- Latency dashboard/debug view.
- Report-ready latency measurements.

Testing:

- Collect at least 30 latency samples.
- Test with internet on.
- Test with internet off.
- Test with Supabase unavailable.
- Confirm local prediction and alert still work without backend.

Exit criteria:

- The app can demonstrate measured local latency and show backend sync is not required for alerts.

## 13. Phase 5: Haptic Alerts and Stress Event Logic

Objective:

Alert the user only when stress is sustained enough to reduce false alarms.

Implementation tasks:

- Implement stress smoothing.
- Recommended first rule: alert when 3 of the last 5 predictions are high stress.
- Add alert cooldown, such as 10 minutes.
- Add Android haptic vibration.
- Add optional notification.
- Add alert actions: dismiss, open chatbot, start breathing exercise.
- Store alert events locally.

Deliverables:

- Stress alert manager.
- Haptic alert pattern.
- Cooldown logic.
- Alert event history.
- Dashboard indication of last alert.

Testing:

- Simulate one high prediction and confirm no immediate alert if smoothing rule is not met.
- Simulate 3 high predictions out of 5 and confirm alert fires.
- Confirm cooldown prevents repeated alerts.
- Confirm alert works when app is foregrounded.
- Confirm notification behavior if app is backgrounded.

Exit criteria:

- High sustained stress produces a haptic alert without repeatedly annoying the user.

## 14. Phase 6: Supabase Auth and Database

Objective:

Persist user data securely and prepare the backend for recommendations.

Implementation tasks:

- Create Supabase project.
- Configure Supabase Auth.
- Add Android Supabase client.
- Create database tables.
- Enable RLS on all user-owned tables.
- Add policies so users can access only their own rows.
- Add profile creation after signup.
- Add health checklist storage.
- Add stress prediction and summary sync.
- Add model version table.

Deliverables:

- Supabase project configured.
- SQL schema migration.
- RLS policies.
- Android auth integration.
- Health checklist persistence.
- Stress history persistence.

Testing:

- Sign up a new user.
- Log in and log out.
- Save checklist data.
- Save stress prediction.
- Confirm another user cannot read the first user's rows.
- Confirm service-role key is not present in Android app.

Exit criteria:

- Authenticated users can securely store and retrieve their own Stress Guard data.

## 15. Phase 7: Background Sync

Objective:

Sync local app data to Supabase without affecting real-time latency.

Implementation tasks:

- Add WorkManager sync worker.
- Sync unsynced stress predictions.
- Sync daily summaries.
- Sync latency metrics.
- Retry failed syncs.
- Mark synced records after successful upload.
- Add sync status to dashboard or settings screen.

Deliverables:

- Background sync worker.
- Retry behavior.
- Sync status display.
- Local-to-Supabase data consistency.

Testing:

- Collect data offline.
- Restore internet and confirm sync occurs.
- Force Supabase failure and confirm records remain unsynced.
- Confirm duplicate records are not created after retry.
- Confirm local prediction latency does not change during sync.

Exit criteria:

- Backend sync is reliable and does not block real-time stress detection.

## 16. Phase 8: Medical Recommendation Module

Objective:

Recommend routine checkups based on sustained stress and user-reported health risk factors.

Implementation tasks:

- Build health checklist screen if not already complete.
- Store checklist in Supabase.
- Create daily stress summary generation.
- Implement weekly rule-based risk score.
- Generate recommendation record with score, level, reason, and text.
- Show recommendation card in app.
- Add clear disclaimer that this is not a diagnosis.

Deliverables:

- Health checklist UI.
- Risk scoring function.
- Weekly recommendation generation.
- Recommendation explanation UI.
- Stored recommendation history.

Testing:

- Low-risk user with low stress receives no checkup recommendation.
- User with one high-stress day receives calming suggestion only.
- User with 5+ high-stress days and smoking receives checkup recommendation.
- User with heart condition and sustained high stress receives elevated/high recommendation.
- Confirm recommendation text explains the reason.

Exit criteria:

- The app can explain why it recommends a checkup without claiming to diagnose disease.

## 17. Phase 9: Analytics Dashboard

Objective:

Replace dummy analytics with real stress and sensor history.

Implementation tasks:

- Show current stress level.
- Show latest heart rate, steps, and sleep.
- Show today's high-stress count.
- Show weekly stress trend.
- Show recommendation status.
- Show watch connection status.
- Show sync status.
- Show latency metrics for demo/report.

Deliverables:

- Real analytics dashboard.
- Stress trend chart.
- Daily/weekly summary cards.
- Recommendation card.
- Latency card.

Testing:

- Confirm dashboard updates with live BLE data.
- Confirm dashboard updates after stress prediction.
- Confirm historical data loads after app restart.
- Confirm empty states are clear.
- Confirm charts handle no-data and partial-data cases.

Exit criteria:

- Dashboard tells a complete story: live data, stress level, history, recommendation, and latency.

## 18. Phase 10: Chatbot Support Module

Objective:

Provide supportive conversation for users who feel stressed.

Implementation tasks:

- Select Hugging Face model for supportive mental health conversation.
- Create backend wrapper endpoint for chatbot requests.
- Keep Hugging Face token on backend.
- Add safety system prompt.
- Add app chatbot screen.
- Add quick action from high-stress alert to open chatbot.
- Add fallback response for unsafe or emergency messages.

Safety boundaries:

```text
The chatbot can provide calming support, breathing prompts, grounding exercises,
and reflective conversation.

The chatbot must not diagnose, prescribe medication, replace therapy, or handle
emergency situations as if it were a medical professional.
```

Deliverables:

- Chatbot backend endpoint.
- Android chatbot UI.
- Safety prompt.
- High-stress alert to chatbot flow.
- Basic chat session storage if required.

Testing:

- Send normal stress message and confirm supportive response.
- Send request for diagnosis and confirm safe refusal.
- Send medication question and confirm safe redirection.
- Test Hugging Face API failure and confirm fallback message.
- Confirm API token is not inside Android app.

Exit criteria:

- User can open chatbot from the app and receive safe supportive responses.

## 19. Phase 11: Model and Report Validation

Objective:

Make the ML implementation defensible for the FYP report and viva.

Implementation tasks:

- Document training dataset.
- Document model features.
- Document model accuracy, precision, recall, F1, and confusion matrix.
- Document ONNX export process.
- Document Python-vs-Android prediction validation.
- Document why binary model was selected for MVP.
- Add model version to every prediction record.

Deliverables:

- ML methodology document.
- Model evaluation table.
- ONNX export notes.
- Android validation results.
- Model version tracking.

Testing:

- Run fixed sample set in Python.
- Run same sample set in Android.
- Compare predictions.
- Confirm model version is stored with predictions.

Exit criteria:

- The team can explain and defend the model pipeline from training to mobile inference.

## 20. Phase 12: End-to-End Demo Polish

Objective:

Prepare the final FYP demo flow.

Implementation tasks:

- Create demo user account.
- Prepare sample health checklist profile.
- Prepare controlled BLE or simulated data scenario.
- Prepare high-stress trigger scenario.
- Prepare recommendation scenario.
- Prepare chatbot demo prompts.
- Prepare latency results table.
- Fix visible UI issues.
- Add graceful error states.

Deliverables:

- Demo-ready Android app.
- Demo script.
- Final test data.
- Final report screenshots.
- Latency and recommendation evidence.

Testing:

- Run full demo from login to recommendation.
- Test with watch connected.
- Test with watch disconnected.
- Test with internet off.
- Test with internet restored.
- Test chatbot fallback.
- Test app restart during unsynced data.

Exit criteria:

- The complete FYP story can be demonstrated in 5-8 minutes without manual database editing.

## 21. Agent Handoff Checklist

Before an agent starts any phase, it should confirm:

- Android project path.
- Current branch and uncommitted changes.
- Whether UI is XML or Jetpack Compose.
- BLE manager location.
- Model artifact location.
- Supabase project status.
- Available test device or emulator.
- Whether Samsung Watch is available for live testing.

For every implementation change, the agent should:

- Keep real-time inference local.
- Avoid blocking the UI thread.
- Preserve existing BLE behavior.
- Add tests or manual verification notes.
- Update this plan or project docs when behavior changes.
- Avoid medical diagnosis language.

## 22. Recommended Build Order

Best order for the team:

```text
1. BLE stabilization
2. ONNX local inference
3. Latency tracking
4. Haptic alerts
5. Local storage
6. Supabase auth and database
7. Background sync
8. Medical recommendation
9. Real dashboard
10. Chatbot
11. Final validation and demo polish
```

This order protects the most important FYP claim first: low-latency real-time stress detection.

## 23. Final MVP Definition

The MVP is complete when:

- User can sign up and log in.
- User can fill health checklist.
- App connects to Samsung Watch through BLE.
- App displays real-time heart rate, steps, and sleep.
- App runs binary ONNX stress detection locally.
- App triggers haptic alert for sustained high stress.
- App records latency measurements.
- App syncs stress history to Supabase.
- App generates rule-based medical checkup recommendations.
- App provides a safe supportive chatbot.
- Dashboard shows live data, history, recommendation, and latency.

## 24. Non-Goals for MVP

Do not build these unless all MVP work is already stable:

- Doctor portal.
- Hospital integration.
- Medication recommendation.
- Disease diagnosis.
- Emergency response system.
- Full therapy chatbot.
- Continuous upload of every raw BLE packet.
- Complex medical ML model without labeled clinical data.

## 25. Report-Friendly Claims

Use these claims:

- Stress Guard uses a hybrid architecture to reduce latency.
- Real-time stress inference runs on-device using ONNX.
- Supabase is used for secure persistence and long-term analysis.
- Medical recommendation is rule-based for explainability and safety.
- The chatbot provides supportive conversation, not diagnosis.
- Backend sync is intentionally outside the critical alert path.

Avoid these claims:

- The app diagnoses disease.
- The chatbot replaces a psychiatrist.
- The medical recommendation model predicts illness.
- Backend inference is required for real-time alerts.

