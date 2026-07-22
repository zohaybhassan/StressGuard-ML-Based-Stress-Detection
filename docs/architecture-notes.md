# StressGuard Architecture Notes

## Current Shape

- Android app in Kotlin
- Samsung Watch message delivery over BLE / Wearable message channel
- Dashboard UI in XML
- Health Connect used for sleep access on phone
- Local encryption helper for watch payloads

## Present Screen Flow

- Launcher router
- Google sign-in screen
- Profile setup form
- Live dashboard

## Data Flow Today

1. Watch sends heart rate and step data.
2. `VitalReceiverService` decrypts and parses the message.
3. The dashboard receives a broadcast and updates the UI.
4. Sleep data is requested from Health Connect when available.

## Planned Next Layers

- Typed sensor models
- Local feature window builder
- ONNX inference service
- Alert manager with cooldown
- Room storage for history and sync queue
- Supabase persistence
- Recommendation module
- Supportive chatbot backend

## Design Rule

Keep the real-time alert path fully local:

- BLE receive
- preprocessing
- inference
- UI update
- haptic alert

Anything network-dependent must happen after the alert path.
