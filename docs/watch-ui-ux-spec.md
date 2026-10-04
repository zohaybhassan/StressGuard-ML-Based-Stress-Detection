# StressGuard Wear OS UI/UX and Feature Specification

**Status:** Proposed implementation specification  
**Scope:** `wear` module only, plus the phone-to-watch messages required to support the experience  
**Target:** Round Wear OS watches, starting with Samsung Galaxy Watch SM-R900  
**Implementation stack:** Kotlin, Jetpack Compose for Wear OS, Health Services, Wearable Data Layer  
**Last updated:** 4 October 2026

---

## 1. Purpose

This document defines the intended user experience, visual language, screens, interaction patterns,
states, features, implementation boundaries, and acceptance checks for the StressGuard Wear OS app.

The watch app is not a smaller copy of the phone app. Its purpose is to let the wearer answer three
questions within a few seconds:

1. What is my latest stress result?
2. Are the sensor reading and phone connection current?
3. What can I do immediately?

The full phone app remains responsible for authentication, model inference, history, trends, profile
management, health checklists, detailed sleep information, and the assistant.

---

## 2. Product principles

### 2.1 Glanceable first

- The primary screen must be understandable within 2–5 seconds.
- Show one dominant result, no more than two supporting metrics, and one primary action.
- Avoid paragraphs, dense charts, multi-column dashboards, and hidden gestures.
- Preserve the Wear OS `TimeText` at the top.

### 2.2 Truthful data

- Never present a stale heart-rate value or stress result as live.
- Never infer stress from heart rate alone.
- Never fabricate sleep, connection, sensor, or synchronization values.
- Always distinguish `LIVE`, `LAST RESULT`, `WAITING`, `WORKOUT MODE`, and error states.
- When the model is outside its trained range, keep the mobile app's asterisk and provide a short
  explanation on the details/status screen.

### 2.3 Calm, not alarming

- StressGuard provides awareness and support; it is not a medical device.
- Use neutral wording such as **Elevated stress signal**, not **Danger**, **Abnormal**, or **Medical
  emergency**.
- Reserve red for a confirmed high-stress model class or a destructive/error action.
- Do not use pulsing red backgrounds, sirens, or continuous vibration.

### 2.4 Immediate action

- A high-stress result should offer a useful response from the same screen.
- The default intervention is a one-minute guided breathing exercise.
- Feedback and alert controls should require only one or two taps.

### 2.5 Phone and watch have separate responsibilities

- The watch measures heart rate and steps and sends fresh readings to the phone.
- The phone remains the source of truth for stress inference and alert policy.
- The phone sends the resulting stress score, class label, age, and alert state back to the watch.
- The watch may cache the latest result for display, but it must label cached data as old/offline.

---

## 3. Stress percentage: source of truth

The number shown in the center of the watch must be the **same gauge score displayed by the mobile
app**.

The mobile app currently calculates the displayed score from the complete model probability vector:

```text
class anchors = evenly spaced from 10 to 90
gauge score   = sum(probability[class] × anchor[class])
display       = rounded integer from 0 to 100, followed by "%"
```

For the shipped binary model, the anchors are 10 for the lower-stress class and 90 for the
higher-stress class. The displayed score is therefore an expected stress score. It is **not** the raw
confidence of the predicted class and must not be described as a medical probability.

### Required behavior

- The phone calculates the score once and sends the final integer to the watch.
- The watch must not independently recreate the model or derive a percentage from heart rate.
- The label shown below the number comes from the phone's predicted class:
  - `RELAXED`
  - `NORMAL`
  - `HIGH STRESS`
  - any future model class after the same mobile display-name conversion
- Do not invent percentage bands for the label. The label is determined by the model class, not by
  arbitrary UI thresholds.
- If the mobile result is `NORMAL*`, the watch must also show `NORMAL*`.
- If there is no valid prediction, display `--` rather than `0%`.
- During workout mode, display `--` and `WORKOUT MODE`; do not imply that a prediction was made.

### Minimum phone-to-watch result payload

| Field | Purpose |
|---|---|
| `score` | Exact integer percentage displayed on the phone |
| `label` | Display label such as `NORMAL` or `HIGH STRESS` |
| `classIndex` and `classCount`, or `severity` | Selects the same semantic color as the phone |
| `predictedAtEpochMs` | Lets the watch show freshness honestly |
| `outOfTrainingRange` | Controls the asterisk and explanatory state |
| `alertDecision` | Fire, cooldown, muted, not sustained, or none |
| `workoutModeUntilEpochMs` | Keeps phone and watch workout states aligned |
| `alertsMutedUntilEpochMs` | Keeps alert controls aligned |

The payload should be versioned so future fields can be added without breaking older watch builds.

---

## 4. Information architecture

### Core app screens

1. Launch and permission onboarding
2. Live dashboard
3. Stress alert
4. Guided breathing
5. Stress check-in
6. Workout mode
7. Alert controls
8. Monitoring and connection status

### Wear OS surfaces outside the main app

1. StressGuard Tile
2. Watch-face complication
3. Stress alert notification actions

### Features that remain on the phone

- Sign-in and account creation
- Profile setup
- Seven-day trends and charts
- Detailed prediction history
- Detailed sleep analysis
- Health checklist
- Full workout history
- Full assistant chat
- Supabase/account settings
- Detailed model explanation and disclaimers

---

## 5. Navigation model

- Launching the app always opens the Live Dashboard.
- Do not add a phone-style bottom navigation bar.
- Use visible buttons or chips for every important action; do not require undiscoverable swipes.
- Use standard Wear OS back behavior and the system swipe-to-dismiss gesture.
- Use a `ScalingLazyColumn` for list-style screens such as Alert Controls and Status.
- Rotary input must scroll every scrollable screen.
- Preserve scroll position when navigating back.
- Use `TimeText` on normal screens. It may fade/scroll away on longer lists.
- Use a confirmation overlay after short actions such as muting alerts or saving feedback.
- Dialogs should contain one decision and no more than two primary choices at a time.

### Navigation map

```text
Launch / permissions
        |
        v
Live Dashboard ---------------------------> Monitoring & Status
     |         |                |
     |         |                +----------> Alert Controls
     |         |
     |         +---------------------------> Workout Mode
     |
     +-------------------------------------> Guided Breathing

Phone high-stress decision ---> Stress Alert ---> Breathing
                                      |
                                      +----------> Quick Check-in
```

---

## 6. Visual design system

### 6.1 Style direction

Use a **minimal, clinical, calm, high-contrast OLED dashboard**.

- Prefer flat surfaces, semantic color, subtle outlines, and one focal progress arc.
- Do not use neumorphism, glass blur, heavy shadows, decorative gradients, or large illustrations.
- Match the mobile app's role-based colors and stress semantics.
- Use true black or near-black as the watch canvas to reduce OLED power and maximize contrast.
- Use icons from one consistent Wear/Material vector icon family. Do not use emoji as interface icons.

### 6.2 Color tokens

These values are derived from the existing StressGuard dark theme. Components should consume
semantic tokens rather than hardcoded colors.

| Token | Hex | Usage |
|---|---:|---|
| `watch_canvas` | `#000000` | OLED watch background |
| `canvas_near_black` | `#081311` | Alternate branded background and ambient surfaces |
| `surface` | `#101D1A` | Cards and raised content |
| `surface_variant` | `#172723` | Secondary cards, disabled containers |
| `outline` | `#22332E` | Subtle borders and dividers |
| `outline_strong` | `#2E423B` | Selected/focused boundaries |
| `brand` | `#2ED3B7` | Primary action, live indicator, selected state |
| `brand_dark_surface` | `#0D3B34` | Stress hero surface |
| `brand_hero_end` | `#16564B` | Optional subtle hero depth |
| `on_brand` | `#04231E` | Text/icons on bright teal |
| `text_primary` | `#E8F1EE` | Primary text and numbers |
| `text_secondary` | `#9CB2AC` | Supporting labels |
| `text_tertiary` | `#6F847E` | Timestamps and disabled text |
| `text_on_dark` | `#FFFFFF` | Stress percentage and high-emphasis values |
| `hero_track` | `#20524A` | Unfilled stress gauge track |
| `stress_low` | `#4FD98F` | Lower-stress class |
| `stress_moderate` | `#FFC463` | Future/intermediate class or warning state |
| `stress_elevated` | `#FF9A5C` | Future/elevated class |
| `stress_high` | `#FF6B6E` | High-stress class |
| `metric_heart` | `#FF7189` | Heart-rate icon/value |
| `metric_steps` | `#6A9BFF` | Steps icon/value |
| `metric_sleep` | `#9E85FF` | Reserved for any phone-provided sleep summary |
| `warning` | `#FFC463` | Partial access, stale data, action required |
| `danger` | `#FF6B6E` | Permission failure, destructive actions |
| `success` | `#4FD98F` | Confirmations and healthy connection state |

#### Color rules

- Do not communicate state using color alone. Pair color with a label and/or icon.
- Use `stress_low`/`stress_high` according to the predicted class supplied by the phone.
- Do not color the ring by arbitrary score thresholds when the underlying class says otherwise.
- Red should never fill the whole screen.
- Normal text must maintain at least 4.5:1 contrast; meaningful icons and controls must maintain at
  least 3:1 contrast against adjacent colors.

### 6.3 Typography

Use the Wear OS system sans-serif/Roboto family. Avoid downloading custom fonts on the watch.

| Role | Recommended size | Weight | Usage |
|---|---:|---|---|
| Time | System `TimeText` | System | Current time |
| Stress percentage | 38–44sp | Bold | Central `36%` value |
| Page title | 18–20sp | Semibold | Breathing, Workout, Status |
| Metric value | 17–20sp | Medium/Semibold | `84 BPM`, steps, timer |
| Status label | 11–13sp | Medium | `NORMAL`, `HIGH STRESS`, `LIVE` |
| Body | 13–15sp | Regular | Instructions and short explanations |
| Caption | 11–12sp | Regular | Last updated, source, secondary state |

Rules:

- Support system font scaling and bold-text accessibility settings.
- Do not reduce important text below 12sp to make a dense layout fit.
- Use tabular figures where available so percentages and timers do not visually jump.
- Avoid more than two lines of body text on the non-scrolling dashboard.
- Truncate only non-critical labels; never truncate a warning or action.

### 6.4 Spacing, shape, and sizing

- Base spacing unit: 4dp.
- Preferred spacing sequence: 4, 8, 12, 16, 20, and 24dp.
- Minimum Android touch target: 48×48dp.
- Minimum gap between adjacent touch targets: 8dp.
- Use percentage-based outer margins on round screens to prevent edge clipping.
- Design small-screen first at 192–216dp; use 225dp as the larger-screen breakpoint.
- Recommended icon sizes: 18dp compact, 24dp standard.
- Recommended chip/button corner radius: 16–24dp or native Wear component shape.
- No card should exceed roughly 60% of screen height on a round display.
- Keep primary controls away from the extreme top and side edges.

### 6.5 Motion and haptics

- Stress gauge update: 650ms decelerating arc animation, matching the phone gauge.
- Animate only when a genuinely new prediction arrives.
- Do not continuously pulse the live dot or heart icon.
- Tap feedback must appear within 80–150ms.
- Breathing animation: smooth 4-second expansion for inhale and 4-second contraction for exhale.
- Provide a light haptic cue when breathing phases change.
- High-stress alert: one short, recognizable haptic sequence; no continuous vibration.
- Respect reduced-motion settings. In reduced motion, replace expanding geometry with text, a static
  progress indicator, and phase-change haptics.
- Ambient mode must disable decorative animation.

---

## 7. Screen specifications

### 7.1 Launch and permission onboarding

#### Purpose

Explain why sensor permissions are needed and recover gracefully from denial.

#### Flow

1. Branded splash screen.
2. Short rationale for heart-rate access.
3. System heart-rate permission request.
4. Short rationale for activity/steps access.
5. System activity-recognition permission request.
6. Separate background-heart-rate explanation and request after foreground access succeeds.
7. Continue to dashboard even if optional background access is declined.

#### Required states

- All permissions granted
- Heart-rate permission missing
- Activity permission missing
- Both foreground permissions missing
- Background permission missing
- Sensor unsupported on the watch
- Permission granted in Settings after an earlier denial

#### UX requirements

- State exactly what will be unavailable after denial.
- Never show a dead button after denial; provide an **Open Settings** action when appropriate.
- Background denial must say: **Monitoring works only while StressGuard is open**.
- Do not request foreground and dependent background permissions in the same system request.

---

### 7.2 Live Dashboard

#### Purpose

Provide the latest phone-computed stress score, current heart rate, steps, connection/freshness, and
one immediate action.

#### Non-scrolling layout

```text
                 5:39

           • LIVE / LAST RESULT

           ╭─────────────╮
          ╱      36%       ╲
         │      NORMAL*      │
          ╲                 ╱
           ╰──────  ───────╯

       ♥ 84 BPM       10 STEPS

          [ Breathe 1 min ]
```

The stress dial should visually match the mobile app:

- Open arc rather than a closed donut
- Start angle approximately 140°
- Total sweep approximately 260°
- Visible gap at the bottom
- Rounded stroke caps
- Stress-class color for the progress arc
- `hero_track` for the unfilled arc
- Percentage centered inside the arc
- Model label directly below the percentage

#### Information hierarchy

1. Stress percentage
2. Stress label and freshness
3. Heart rate and steps
4. Breathing action

#### Required display states

| State | Gauge | Status copy | Supporting behavior |
|---|---|---|---|
| Live prediction | Current score | `NORMAL`, `HIGH STRESS`, etc. | Show `LIVE` |
| Outside trained range | Current score | Label with `*` | Details explain the asterisk |
| Waiting for first result | `--` | `WAITING FOR PHONE` | Show HR/steps if available |
| Cached phone result | Last score, visually muted | `LAST RESULT` | Show age such as `2 min ago` |
| Result older than freshness limit | Last score or `--` | `RESULT OUT OF DATE` | Do not show `LIVE` |
| Workout mode | `--` | `WORKOUT MODE` | Show workout shortcut |
| Phone unreachable | Cached result if available | `PHONE OFFLINE` | Show connection/status shortcut |
| HR acquiring | Stress state unchanged | `MEASURING HEART RATE` | Show progress state for HR |
| Watch off wrist | Stress state unchanged/cached | `WEAR WATCH SNUGLY` | Do not show HR as live |
| Sensor permission missing | `--` if no result | `PERMISSION NEEDED` | Show **Open Settings** |
| Model/pipeline error | `--` or last safe result | `RESULT UNAVAILABLE` | Never expose stack traces |

#### Freshness rules

- A sensor sample reaches stale status after the project's existing 30-second threshold.
- A stale value must include its age, for example `84 BPM · 45s ago`.
- The watch stress result must use the timestamp sent by the phone.
- `LIVE` is allowed only when both the phone connection and result freshness conditions are true.
- Connection and data freshness are separate: a connected phone can still have no current reading.

#### Interactions

- Tap the central gauge: open Monitoring and Connection Status.
- Tap heart rate/steps: optionally open a lightweight Today summary in a later phase.
- Tap **Breathe 1 min**: open Guided Breathing.
- Long press is not required for any core action.

---

### 7.3 Stress Alert

#### Trigger

The phone sends an alert event only after the existing policy fires: at least three of the last five
predictions are high stress, the ten-minute cooldown has expired, alerts are not muted, and workout
mode is inactive.

#### Layout

```text
        Elevated stress signal

       Based on recent readings
              72%

        [ Breathe 1 min ]
        [ I'm okay ]
```

#### Behavior

- Use a short haptic sequence on arrival.
- Use the high-stress color for the icon/ring, not the entire background.
- Primary action: **Breathe 1 min**.
- Secondary action: **I'm okay**.
- Optional tertiary action in an overflow/details screen: **Mute alerts**.
- Dismissal must be possible through standard Wear OS back/swipe behavior.
- Do not repeatedly alert while the phone policy is in cooldown.
- If the event arrives late, label it with its actual age instead of presenting it as new.

#### Copy rules

- Preferred: **Elevated stress signal detected**.
- Supporting text: **Based on your recent readings**.
- Avoid: **You are stressed**, **Danger**, **Abnormal**, or medical advice.

---

### 7.4 Guided Breathing

#### Purpose

Provide a short, watch-native intervention without requiring the phone.

#### Layout and phases

- Page title: **Breathe**.
- Large central breathing ring.
- Central phase label: **Inhale** / **Exhale**.
- Remaining time below the ring.
- Visible pause/resume control.
- Visible end control.

#### Default session

- Duration: 60 seconds.
- Rhythm: 4 seconds inhale, 4 seconds exhale.
- One light haptic at each phase transition.
- Do not claim that breathing lowers stress or provides treatment.

#### Completion

- Show a confirmation overlay: **Breathing complete**.
- Offer **How do you feel?** only as an optional next step.
- Return to the dashboard after confirmation or dismissal.
- Do not compare before/after HR as proof of effectiveness.

---

### 7.5 Stress Check-in

#### Purpose

Collect simple watch feedback after an alert while keeping detailed labeling on the phone.

#### Step 1

```text
Were you feeling stressed?

[ Yes ]    [ No ]
```

#### Step 2, only after Yes

- Optional severity scale from 1 to 5.
- Use large, discrete choices that work with rotary input.
- Labels at the ends: **Mild** and **Strong**.
- Provide **Skip**.

#### Data behavior

- Associate feedback with the exact alert/prediction identifier supplied by the phone.
- Prevent duplicate submission.
- Show **Response saved** confirmation.
- If the phone is offline, queue the response locally and label it **Waiting to sync**.
- Do not display raw model identifiers or technical confidence vectors.

---

### 7.6 Workout Mode

#### Purpose

Prevent exercise-related heart-rate elevation from being interpreted as stress while showing useful
workout measurements.

#### Inactive state

- Title: **Workout mode**.
- Short explanation: **Stress predictions pause while workout mode is active**.
- Quick duration choices: 30 minutes, 1 hour, 90 minutes, 2 hours.

#### Active state

- Large elapsed/remaining timer.
- Current heart rate.
- Average heart rate if supplied by the phone/session logic.
- Steps during the session.
- Pause/resume action.
- End workout action, visually separated as a terminating action.

#### Behavior

- Phone and watch must display the same workout state and end time.
- Starting from either device updates the other device.
- Dashboard stress percentage becomes `--` with `WORKOUT MODE`.
- Ending the workout shows a confirmation and returns to the dashboard.
- Stress inference resumes according to the existing phone pipeline; do not create a separate
  watch-only inference rule.

---

### 7.7 Alert Controls

#### Options

- Mute for 10 minutes
- Mute for 30 minutes
- Mute for 1 hour
- Mute for 4 hours
- Resume alerts now

#### UX requirements

- Show the current state at the top: **Alerts active** or **Muted until 6:30 PM**.
- Monitoring and predictions continue while alerts are muted; say this explicitly.
- Selection produces a confirmation overlay and a light haptic.
- Watch and phone must share the same mute-until timestamp.
- Expired mute state should clear automatically.

---

### 7.8 Monitoring and Connection Status

#### Purpose

Provide a single place to understand why data is missing without cluttering the dashboard.

#### Rows

- Phone connection: connected, unreachable, or checking
- Heart-rate sensor: live, acquiring, off wrist, unsupported, or permission missing
- Steps sensor: live, unsupported, or permission missing
- Background monitoring: active or foreground only
- Latest watch reading age
- Latest phone stress result age
- Pending feedback/data waiting for phone
- App version

#### Actions

- Open permission settings
- Retry connection/status check
- Open alert controls
- Open phone app, if supported by the chosen Wear Data Layer flow

#### Copy requirements

- Prefer a cause and recovery action: **Heart-rate access is off — open Settings**.
- Do not use ambiguous states such as **Disconnected** when the watch is connected but simply not
  producing a sensor reading.
- Do not expose internal exceptions or developer terminology to the wearer.

---

## 8. Wear OS Tile

The tile provides the fastest glance without opening the app.

### Tile contents

- Stress percentage
- Stress label
- Result freshness, for example **2 min ago**
- Compact HR and steps values
- Primary action: **Breathe**
- Secondary action: open the app

### Tile rules

- Show `--` when no valid result exists.
- Never label cached data as live.
- Use a non-scrolling tile layout.
- Keep touch targets large and content inside curved screen margins.
- Avoid frequent forced updates; update after a new phone result and within platform limits.
- If the tile is stale, show the timestamp and a refresh/open-app affordance.

---

## 9. Watch-face complication

### Recommended complication options

- Short text: `36% Normal`
- Monochromatic icon plus score: shield-heart + `36%`
- No-data state: StressGuard icon or `--`

### Behavior

- Tapping opens the Live Dashboard.
- Complication freshness must follow platform update limits.
- Do not attempt second-by-second HR display.
- In ambient mode, use monochromatic artwork and avoid gradients.
- If the result is stale, use `--` or a clearly old value based on complication space.

---

## 10. Notification actions

For a high-stress notification on the watch, provide:

1. **Breathe 1 min**
2. **I'm okay**
3. **Open on phone**

Requirements:

- Actions must map to the same alert identifier stored by the phone.
- A handled alert must not produce duplicate check-ins.
- Notification copy must match the calm, non-diagnostic alert language.
- Respect cooldown, alert mute, workout mode, and notification permission state.

---

## 11. Component patterns

| Pattern | Use | Rules |
|---|---|---|
| Open progress arc | Stress percentage | 260° sweep, bottom gap, rounded caps |
| Status pill | `LIVE`, muted, workout | Icon/dot + text; color never stands alone |
| Metric pair | HR and steps | Equal visual weight below stress hero |
| Edge-hugging primary button | Breathe/start/confirm | One primary action per screen |
| Filled tonal chip | Secondary action | Minimum 48dp target |
| Scaling list | Settings/status/durations | Rotary scrolling and scroll indicator |
| Confirmation overlay | Saved/muted/completed | Brief, non-blocking, haptic confirmation |
| Full-screen alert | Sustained stress alert | Short copy and two clear actions |
| Empty/loading state | Waiting for data | Explain what is happening and what to do |

### Anti-patterns

- Phone-style bottom navigation
- Tiny icon-only controls
- Multiple competing primary buttons
- Full-screen red backgrounds
- Constant pulse/glow animations
- Pie/donut charts with several categories
- Long diagnostic strings on the home screen
- Gesture-only critical actions
- Showing `0%` when prediction data is absent
- Treating phone reachability and fresh sensor data as the same state
- Calculating watch stress from HR alone

---

## 12. Accessibility requirements

- Every interactive element must have a minimum 48×48dp touch target.
- Add at least 8dp between adjacent touch targets.
- Every meaningful icon requires a clear content description unless equivalent visible text already
  provides the same meaning.
- Decorative icons must be excluded from the accessibility tree.
- The central gauge must announce a complete phrase, for example:
  **Stress score 36 percent, normal, updated now**.
- A stale gauge must announce its age.
- Do not announce every sensor sample as a live-region update; this would overwhelm TalkBack users.
- Reading/focus order must follow the visible hierarchy.
- Support font scaling without clipping at 192dp.
- Use text/icon shape in addition to color for every state.
- Support reduced motion.
- Haptics supplement visual/text feedback; they never replace it.
- All scrolling screens must work with touch and rotary input.
- Dismissal/back navigation must remain predictable.

---

## 13. Battery and performance requirements

- Continue using Health Services/passive monitoring rather than aggressive custom polling.
- Do not increase sensor frequency solely to animate the screen.
- Recompose only the components whose state changed.
- Do not run continuous animations on the dashboard.
- Suspend non-essential animation in ambient mode and when the screen is not visible.
- Do not perform model inference on the watch in this version.
- Use the paired phone for cloud synchronization and Supabase operations.
- Avoid network calls directly from the watch unless a future standalone architecture explicitly
  requires them.
- Throttle Data Layer status traffic; do not send the same prediction repeatedly.
- Tile and complication updates must follow platform update limits.
- Validate background monitoring battery impact over a full day, not only during a short demo.

---

## 14. Privacy, safety, and trust

- Keep Supabase secrets and Google OAuth secrets off the watch.
- Reuse the existing protected phone/watch transport and application-level payload protection.
- Store only the minimum cached result and unsent feedback needed for offline recovery.
- Clear user-specific cached watch state on phone sign-out/account change.
- Do not expose detailed personal health data on a complication beyond the user's chosen option.
- Avoid logging raw personal health data in production builds.
- Do not describe the result as a diagnosis or medical probability.
- Provide a concise disclaimer in the phone app and an abbreviated explanation in watch details.
- Crisis/emergency functionality should not be invented from a stress percentage. Direct emergency
  guidance remains the responsibility of explicit phone-side crisis flows.

---

## 15. Offline and failure behavior

| Condition | Required behavior |
|---|---|
| Phone temporarily unreachable | Keep measuring; show `PHONE OFFLINE`; cache latest valid result with age |
| Watch reconnects | Resend/refresh state once; avoid duplicate prediction events |
| Phone app process stopped | Watch shows connection/result age honestly; no fake live state |
| No HR because watch is off wrist | Show `WEAR WATCH SNUGLY`; steps may continue |
| HR sample is older than 30 seconds | Show age and remove live presentation |
| Permission revoked | Stop affected collection and provide Settings recovery action |
| Background permission denied | Continue foreground operation and explain limitation |
| Model unavailable/error | Keep raw metrics available; show `RESULT UNAVAILABLE` |
| Out-of-range model input | Preserve score and label with `*`; explain limitation |
| Feedback submitted offline | Queue once and show `WAITING TO SYNC` |
| Watch restarts | Restore safe cached UI state, re-register passive collection, refresh phone state |
| Date changes at midnight | Reset/reconcile daily steps without showing a negative or impossible total |

---

## 16. Implementation phases

### Phase 1: dashboard foundation

- Apply watch color, typography, spacing, and component tokens.
- Replace plain multiline text with the Live Dashboard.
- Present HR freshness, steps, phone connection, permissions, and off-wrist states.
- Add the breathing entry button, even if the first version opens a placeholder.

### Phase 2: bidirectional stress result

- Add a versioned phone-to-watch prediction message.
- Send the exact mobile gauge score, class label, severity, timestamp, and out-of-range flag.
- Cache the latest watch-visible result.
- Validate exact percentage/label parity between phone and watch.

### Phase 3: intervention and alert flow

- Guided breathing screen.
- Watch stress alert with haptic feedback.
- Quick Yes/No check-in.
- Alert mute controls and phone/watch state synchronization.

### Phase 4: workout and system surfaces

- Workout controls synchronized with phone state.
- StressGuard Tile.
- Watch-face complication.
- Notification actions.

### Phase 5: polish and validation

- Accessibility and font-scaling fixes.
- Ambient/reduced-motion behavior.
- Battery profiling.
- Offline/reconnection and duplicate-event testing.
- Full device-size and real-watch QA.

---

## 17. Acceptance criteria

### Dashboard parity

- [ ] For the same prediction, phone and watch display the exact same percentage.
- [ ] Phone and watch display the same class label.
- [ ] Phone and watch use the same semantic severity color.
- [ ] The outside-trained-range asterisk is preserved.
- [ ] `0%` is never used as a substitute for missing data.
- [ ] Workout mode shows no stress percentage.

### Sensor and freshness

- [ ] Current HR displays with `BPM`.
- [ ] HR older than 30 seconds displays its age and is not marked live.
- [ ] Steps represent the best available daily total and never become negative.
- [ ] Off-wrist behavior is distinguishable from phone disconnection.
- [ ] Sensor unsupported and permission-denied states are distinguishable.
- [ ] Background-permission denial does not break foreground measurement.

### Connectivity and synchronization

- [ ] Phone reachability is checked independently of sensor availability.
- [ ] Cached results are labelled with their age.
- [ ] Reconnection refreshes state without duplicating predictions or alerts.
- [ ] Phone sign-out/account change clears user-specific watch state.
- [ ] Mute and workout state changes propagate both directions.

### Alert behavior

- [ ] Watch alerts only after the phone's 3-of-5 policy fires.
- [ ] Ten-minute cooldown is respected.
- [ ] Muted alerts remain muted on both devices.
- [ ] Workout mode suppresses stress prediction/alerts according to phone logic.
- [ ] Alert actions attach to the correct alert identifier.
- [ ] Duplicate feedback is rejected.

### Breathing and workout

- [ ] Breathing session works without a live phone connection.
- [ ] Breathing has pause, resume, and exit controls.
- [ ] Reduced-motion mode remains usable.
- [ ] Workout start/end state matches the phone.
- [ ] Ending a workout returns to a valid dashboard state.

### Visual and interaction QA

- [ ] Layout works at 192, 204, 216, 225, and 240dp.
- [ ] Round-screen edges do not clip text or controls.
- [ ] Larger font and bold-text settings do not hide critical content.
- [ ] Every interactive target is at least 48×48dp.
- [ ] Adjacent targets have at least 8dp separation.
- [ ] Pressed, disabled, loading, and selected states are visually clear.
- [ ] Normal text contrast is at least 4.5:1.
- [ ] Meaningful icon/control contrast is at least 3:1.
- [ ] No state is communicated by color alone.
- [ ] TalkBack focus order matches visual order.
- [ ] Gauge semantics announce score, label, and freshness as one phrase.
- [ ] Rotary scrolling works on all scrolling screens.
- [ ] System back/swipe dismissal behaves consistently.

### Battery and stability QA

- [ ] Dashboard has no continuous decorative animation.
- [ ] Sensor frequency is not increased for UI animation.
- [ ] Ambient mode disables non-essential animation.
- [ ] Passive/background registration survives normal screen timeout.
- [ ] Watch restart restores background collection.
- [ ] A full-day battery test shows acceptable impact.
- [ ] Tile/complication updates remain within platform limits.
- [ ] No model, network, or permission exception causes an app crash.

### Privacy and copy QA

- [ ] No OAuth secret, Supabase secret, or service-role key is included in the watch app.
- [ ] Production logs avoid raw personal health information.
- [ ] Copy does not claim diagnosis, certainty, or medical treatment.
- [ ] High-stress copy remains calm and actionable.
- [ ] Complication content respects the user's chosen privacy level.

---

## 18. Recommended real-device test scenarios

1. Watch worn correctly with phone connected and fresh HR.
2. Watch removed from wrist while phone remains connected.
3. Phone Bluetooth/Wi-Fi disabled while the watch continues measuring.
4. Phone reconnects after several minutes offline.
5. Heart-rate permission denied, then granted from Settings.
6. Steps permission denied while HR remains allowed.
7. Background health permission denied.
8. Screen turns off before passive registration completes.
9. Incoming normal prediction, then high-stress prediction.
10. High-stress policy reaches three of five readings.
11. Alert repeated within cooldown.
12. Alerts muted from phone, then resumed from watch.
13. Workout started on phone and ended on watch.
14. Feedback submitted while phone is offline and synchronized later.
15. Midnight step reset and watch reboot.
16. Font scaling/bold text enabled on a 192dp emulator.
17. TalkBack and rotary input navigation.
18. Ambient mode and reduced-motion setting.
19. Tile and complication with fresh, stale, and absent data.
20. Phone user signs out while cached watch data exists.

---

## 19. Reference guidance

- [Wear OS design principles](https://developer.android.com/design/ui/wear/guides/get-started/design-for-wearables/principles)
- [Design for Wear OS and round screens](https://developer.android.com/design/ui/wear/guides/get-started/design-for-wearables)
- [Adaptive Wear OS design](https://developer.android.com/design/ui/wear/guides/foundations/adaptive-design)
- [Wear OS app layouts](https://developer.android.com/design/ui/wear/guides/surfaces/apps/layouts)
- [Wear OS non-scrolling layouts](https://developer.android.com/design/ui/wear/guides/surfaces/apps/layouts/non-scrolling)
- [Jetpack Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility)

---

## 20. Definition of done

The watch experience is complete when a wearer can glance at an exact, current phone-computed stress
percentage; understand whether it is live, cached, unavailable, or paused; see current HR and steps;
start a one-minute breathing exercise; respond to a sustained-stress alert; control alert mute and
workout state; and recover from permission, sensor, connection, and offline failures without needing
developer knowledge.

