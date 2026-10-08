# StressGuard Mobile UI Design Specification

Version: 1.1  
Status: Canonical mobile UI source of truth  
Platform: Android phone  
Visual direction: Time-aware, calm, premium wellness dashboard with paired day and night themes  

## 1. Purpose

This document defines the visual system, information hierarchy, interaction rules, page layouts, component patterns, responsive behavior, accessibility requirements, and quality checks for the StressGuard Android application.

It is the source of truth for future mobile UI implementation. Screens should follow this document instead of independently choosing colors, spacing, card styles, icon treatments, or interaction behavior.

The supplied reference contains three related dashboard concepts rather than three separate application pages. They are consolidated as follows:

- The **right-hand concept** defines the canonical Home page hierarchy: greeting, current stress, vitals, insight, and primary actions.
- The **center concept** defines the canonical Trends page: summary metrics, stress chart, key drivers, and workout entry point.
- The **left-hand concept** supplies the semicircular stress gauge, metric-card styling, and detailed timeline treatment. These patterns may be used where specified, but must not create a second competing Home layout.
- The bottom navigation must always reflect the actual page. Unlike the reference collage, Trends must highlight **Trends**, not Home.

The intended experience is supportive and informative, not clinical or alarming. StressGuard presents a wellness estimate, not a medical diagnosis.

---

## 2. Product Experience Principles

### 2.1 Calm before density

The app may contain substantial health information, but it must never feel like an engineering console. Each screen should have one dominant answer and reveal supporting details in a predictable order.

### 2.2 One glance, one meaning

The Home screen must answer these questions immediately:

1. What is my current stress estimate?
2. Is the reading current and trustworthy?
3. What contributed to it?
4. What can I do next?

### 2.3 Supportive, non-diagnostic language

Use language such as:

- “Your body signals look balanced right now.”
- “Your recent readings suggest elevated stress.”
- “Try a short breathing session.”
- “Not enough recent data yet.”

Avoid language such as:

- “You have anxiety.”
- “You are medically stressed.”
- “Dangerous condition detected.”
- “Your health is bad.”

### 2.4 Confidence through transparency

Always communicate whether data is live, recent, stale, simulated, unavailable, or outside the model's trained range. Do not present old or incomplete data as a live reading.

### 2.5 Consistency over decoration

The same stress state must use the same color, label, icon treatment, and tone everywhere: Home, Trends, alerts, watch state, history, and recommendations.

---

## 3. Screen Foundation

### 3.1 Canonical canvas

- Design baseline: `360 × 800 dp` Android phone.
- Support compact phones down to `320 dp` width.
- Support large phones, landscape, and tablets without stretching cards edge to edge.
- Maximum readable content width on large screens: `600 dp`.
- Horizontal page gutter: `16 dp` on compact phones, `20–24 dp` on larger phones.
- Content begins below the status-bar inset and ends above the navigation/gesture inset.
- All screens are edge-to-edge, but interactive content must remain inside safe insets.

### 3.2 Spacing scale

Only use the following spacing tokens:

| Token | Value | Typical use |
|---|---:|---|
| `space-1` | 4 dp | Icon-to-label micro gap |
| `space-2` | 8 dp | Tight internal spacing |
| `space-3` | 12 dp | Card-row gap |
| `space-4` | 16 dp | Default card padding and page gutter |
| `space-5` | 20 dp | Large card padding |
| `space-6` | 24 dp | Section separation |
| `space-8` | 32 dp | Major content separation |
| `space-10` | 40 dp | Authentication hero spacing |

Do not introduce arbitrary values such as `13 dp`, `19 dp`, or `27 dp` without a documented technical reason.

### 3.3 Grid

- The Home vitals area uses a three-column grid when width permits.
- On widths below `340 dp`, vitals may use a horizontally scrollable row or a two-column grid with the third item on the next row.
- Equal cards in the same row must have equal height.
- Text baselines, icon centers, and card edges must visibly align.

---

## 4. Color System

StressGuard has two equally complete visual modes. Day mode uses an airy sea-glass canvas, white and pale-mint surfaces, deep green ink, and restrained wellness accents. Night mode uses near-black green/blue surfaces, luminous mint accents, restrained gradients, and high-contrast text. The content hierarchy, state meanings, data, and available actions must remain identical between modes.

### 4.1 Automatic theme schedule

- **Day:** `06:00–17:59` local device time.
- **Night:** `18:00–05:59` local device time.
- Re-evaluate the active theme when the application process starts and whenever an Activity resumes.
- Do not maintain separate feature sets or navigation structures for day and night.
- Debug builds may provide a temporary day/night preview override, but production behavior must follow local device time.

### 4.2 Day core tokens

| Role | Hex | Usage |
|---|---|---|
| `canvas` | `#F2F9F7` | Main daylight background |
| `surface_1` | `#FFFFFF` | Primary daylight card surface |
| `surface_2` | `#EAF4F1` | Tonal daylight card surface |
| `brand_primary` | `#087A61` | Primary actions, selected states, and normal stress |
| `brand_deep` | `#05614D` | Pressed and high-emphasis green |
| `brand_soft` | `#D9F5EA` | Selected navigation and quiet brand containers |
| `text_primary` | `#102521` | Titles and primary values |
| `text_secondary` | `#405D56` | Body copy and secondary values |
| `text_tertiary` | `#5B746E` | Metadata and inactive navigation |
| `hero_start` | `#E7F6F1` | Day stress hero gradient start; distinct from the page canvas |
| `hero_end` | `#D7EEE7` | Day stress hero gradient end |
| `hero_track` | `#BADCD3` | Day stress-gauge track |

Day semantic text accents must use their contrast-safe values on white: normal `#087A61`, moderate `#8A5500`, elevated `#A84416`, high `#C52E50`, heart `#C83252`, steps `#0B65B4`, and sleep `#7950CC`.

### 4.3 Night core tokens

| Role | Hex | Usage |
|---|---|---|
| `canvas` | `#04110F` | Main app background |
| `canvas_blue` | `#06131C` | Subtle blue-toned background variation |
| `surface_1` | `#0A1D1A` | Standard card surface |
| `surface_2` | `#0D202B` | Blue-toned analytical card |
| `surface_3` | `#152033` | Elevated/interactive card |
| `surface_overlay` | `#182538` | Menus, sheets, selected tiles |
| `hero_start` | `#073F35` | Stress hero gradient start |
| `hero_end` | `#0B2C29` | Stress hero gradient end |
| `border_subtle` | `#1B3733` | Default one-pixel card border |
| `border_blue` | `#1A3146` | Blue card border |
| `text_primary` | `#F4FAF8` | Titles and primary values |
| `text_secondary` | `#B0C0BC` | Body copy and secondary values |
| `text_tertiary` | `#7F928D` | Metadata, hints, inactive navigation |
| `brand_primary` | `#2DE5A7` | Primary action and selected state |
| `brand_secondary` | `#19B994` | Secondary brand accent |
| `brand_deep` | `#08705D` | Pressed/dark brand state |
| `cyan` | `#36A6FF` | Steps, links, resting state |
| `purple` | `#9A63F6` | Sleep and personal insight |
| `heart` | `#FF6179` | Heart rate |
| `amber` | `#F5B83D` | Elevated/tense state |
| `orange` | `#F47A45` | Elevated-to-high transition |
| `danger` | `#F05267` | High stress and destructive action |
| `white` | `#FFFFFF` | Maximum-emphasis content |

### 4.4 Stress semantic tokens

| State | Color | Label |
|---|---|---|
| Rest | `#36A6FF` | Rest |
| Normal | `#2DE5A7` | Normal |
| Elevated | `#F5B83D` | Elevated |
| High | `#F05267` | High stress |
| Unknown | `#7F928D` | Waiting for data |

Color must never be the only state indicator. Always pair it with visible text and, where useful, an icon.

### 4.5 Metric tokens

- Heart rate: `#FF6179` with a deep rose-tinted surface.
- Steps/activity: `#36A6FF` with a deep blue-tinted surface.
- Sleep: `#9A63F6` with a deep violet-tinted surface.
- Workout: `#2DE5A7` with a deep teal surface.
- Insight: violet gradient from `#493889` to `#2C245E`.

### 4.6 Gradients

Gradients should create depth, not decoration.

- Main stress card: `hero_start → hero_end`, top-left to bottom-right.
- Primary action: `#55E1BE → #31CDA8`.
- Assistant action: `#4B347E → #312252`.
- Analytical card: `#102639 → #0B1C29`.
- Canvas may contain a very subtle teal or blue radial glow at no more than approximately 12% visual opacity.

Do not place more than one strong gradient in the same visual section.

---

## 5. Typography

Use Android's system sans-serif/Roboto unless a project-wide bundled font is deliberately introduced. Do not depend on a network-downloaded font.

| Style | Size | Weight | Line height | Usage |
|---|---:|---:|---:|---|
| Display metric | 52–60 sp | 700 | 60–66 sp | Main stress score |
| Page greeting | 32 sp | 700 | 36 sp | User name/greeting |
| Page title | 24 sp | 700 | 30 sp | Authentication and detail pages |
| Card title | 17–18 sp | 600–700 | 22–24 sp | Section/card headings |
| Primary metric | 24–28 sp | 700 | 30–34 sp | Heart rate, steps, sleep |
| Body | 14 sp | 400 | 20 sp | Descriptions |
| Supporting | 13 sp | 400 | 18 sp | Secondary card copy |
| Label | 12 sp | 500–600 | 16 sp | Metadata and chart legends |
| Micro label | 11 sp | 500 | 14 sp | Tooltips and timestamps |

Rules:

- Never use body text smaller than `12 sp`.
- Important health metrics should use tabular numerals where supported.
- Use sentence case, not all caps, except a very short status badge.
- Do not use more than three weights on one screen.
- Support Android font scaling up to at least 200% without clipping critical text.

---

## 6. Shapes, Borders, and Elevation

### 6.1 Corner radii

| Component | Radius |
|---|---:|
| Large hero card | 24 dp |
| Standard card | 18–20 dp |
| Small metric tile | 16 dp |
| Input field | 14–16 dp |
| Button | 16 dp or full pill |
| Chip/badge | 999 dp |
| Modal bottom sheet | 28 dp top corners |

### 6.2 Borders

- Default card border: `1 dp`, `border_subtle`.
- Selected card border: `1.5–2 dp`, semantic/brand color.
- Input focus border: `2 dp`, `brand_primary`.
- Error border: `2 dp`, `danger`.
- Avoid double borders and heavy outlines.

### 6.3 Elevation

The visual style is surface-and-border based. Use minimal shadows.

- Standard cards: `0–2 dp` visual elevation.
- Floating overlays and menus: `6–8 dp`.
- Selected bottom-navigation pill may use a restrained colored glow.
- Do not use large black drop shadows around every card.

---

## 7. Icon and Illustration System

- Use one rounded vector icon family throughout the app.
- Do not use operating-system emoji glyphs as structural icons.
- Mood faces must be custom vector assets so their appearance does not vary by phone manufacturer.
- Standard icon sizes: `20 dp`, `24 dp`, and `28 dp`.
- Icon badges may use a `36–44 dp` tinted circular container.
- Interactive icon-only controls require a minimum `48 × 48 dp` touch target and a content description.
- Decorative artwork must be excluded from accessibility focus.

### 7.1 Brand use

- App headers use the shield/leaf StressGuard symbol and the “StressGuard” wordmark.
- Launcher icons use the symbol only, never the full wordmark.
- Preserve logo proportions and clear space.
- Do not stretch, recolor inconsistently, or place the logo on a similarly colored surface without contrast.

### 7.2 Time-based home illustrations

The Home header uses paired production illustrations that share the same composition and preserve a quiet area for the greeting.

- **Day artwork:** bright lake, layered blue-green mountains, soft clouds, foliage, and a warm rising sun.
- **Night artwork:** deep indigo bedroom/city scene, soft interior light, skyline, stars, and a crescent moon.
- The artwork fills the header, uses a theme-specific scrim, and must never reduce greeting or avatar legibility.
- Both assets are decorative and excluded from accessibility focus.
- Sun and moon imagery communicates time-of-day atmosphere only; it must not imply live weather.
- Use `@drawable/header_scene`; Android's `drawable-night` resource automatically supplies the matching night scene.

---

## 8. Shared Components

### 8.1 App header

Two allowed forms:

1. **Brand header:** logo, StressGuard wordmark, short tagline, notification action, profile avatar.
2. **Personal greeting header:** “Good morning/afternoon/night,” user name, supportive subtitle, profile avatar, and the matching day/night illustration.

Rules:

- A scenic personal header may be approximately `160–180 dp`, excluding the system inset; non-scenic headers remain `72–112 dp`.
- Profile avatar: `44–48 dp`, circular, deep teal/blue surface, initial centered.
- Notification icon: `48 dp` touch target.
- Never show both a large brand header and a large greeting header on the same screen.

### 8.2 Card

- Standard padding: `16 dp`.
- Large hero padding: `20 dp`.
- Card title aligns top-left.
- Actions such as “See all” align top-right and use a chevron.
- Card copy should not exceed three lines without expansion/navigation.

### 8.3 Primary button

- Minimum height: `52 dp`.
- Full-width for authentication and form completion.
- Brand gradient or solid `brand_primary` background.
- Dark foreground text when contrast is sufficient; otherwise use white.
- Loading state keeps the button width stable and replaces the label with a progress indicator plus accessible loading text.

### 8.4 Secondary button

- Minimum height: `48–52 dp`.
- Dark surface, subtle border, primary text.
- Selected state uses a brand-tinted background and brand border.

### 8.5 Status chip

- Height: `28–32 dp`.
- Rounded pill.
- Includes icon/dot and text, for example “Live”, “Watch connected”, or “Offline”.
- Never use only a colored dot without text.

### 8.6 Metric card

Each card contains:

1. Metric icon.
2. Primary value with unit.
3. Metric name.
4. Optional sparkline or compact bar chart.
5. Optional comparison text such as “Slightly higher”.

The chart is decorative unless it has a complete accessible summary.

### 8.7 Empty state

Every data screen must provide:

- A relevant icon/illustration.
- A direct title.
- One sentence explaining why data is unavailable.
- One useful action where possible.

Example:

> No trend yet  
> Wear your watch across at least two days to compare your readings.

### 8.8 Bottom navigation

Exactly three destinations:

- Home
- Trends
- Assistant

Specifications:

- Height: approximately `72 dp` plus bottom system inset.
- Dark translucent/solid surface with a subtle top divider.
- Icon: `24 dp`.
- Label: `11–12 sp`.
- Selected item receives a tinted rounded background/glow and the page accent color.
- Unselected items use `text_tertiary`.
- Preserve state when switching tabs.
- Detail and form screens do not need the bottom navigation; they use a top back button.

---

## 9. Page Specifications

## 9.1 Splash and App Launch

### Purpose

Create immediate brand recognition while authentication/session state loads.

### Layout

- Full-screen dark teal gradient.
- Centered StressGuard shield symbol, approximately `88–104 dp`.
- Wordmark below the symbol.
- Optional short tagline: “Your daily wellness companion.”
- Small indeterminate progress indicator near the lower third only when routing takes longer than approximately 400 ms.

### Behavior

- Do not show a white frame between launch and the first screen.
- Logo transition should be a gentle fade/scale, not a bounce.
- Route to authentication, profile setup, or Home after session restoration.

---

## 9.2 Sign In

### Structure

1. Compact brand block.
2. “Welcome back” heading.
3. Supporting sentence.
4. Email field.
5. Password field with visibility toggle.
6. Primary “Sign in” button.
7. “Create an account” link.
8. Divider labelled “or”.
9. Outlined official Google sign-in button.
10. Privacy/supporting message.

### Visual rules

- Authentication card uses `surface_1`, a subtle border, `20–24 dp` radius, and `20 dp` padding.
- Fields are at least `56 dp` high.
- Labels remain visible when text is entered; do not rely on placeholders alone.
- Google branding must use the official Google mark and its usage rules.

### States

- Inline email/password validation.
- Authentication errors appear above the primary action or directly below the relevant field.
- Loading disables duplicate submission without changing layout.
- Google cancellation is not displayed as a system failure.
- Configuration/OAuth errors must explain the corrective action in development builds without exposing credentials.

---

## 9.3 Create Account and Set Password

### Structure

- Reuse the authentication card and field system.
- Show clear progress when account creation has multiple stages.
- Set Password explains why a password is requested after Google registration.
- Password requirements remain visible before submission.

### Requirements

- Provide show/hide-password controls.
- Confirm password matches before network submission.
- Preserve input after recoverable errors.
- Never block paste or password-manager autofill.

---

## 9.4 Profile Setup

### Purpose

Collect the model profile inputs without presenting the screen like a medical form.

### Layout

1. Back/brand header.
2. Progress label such as “Set up your profile”.
3. Short explanation of why the information is needed.
4. Display name.
5. Age.
6. Gender selection.
7. Occupation selection.
8. BMI category selection.
9. Privacy note.
10. Full-width “Continue” button.

### Rules

- Use one-column layout on phones.
- Dropdown menus use `surface_overlay` and match the field width.
- Helper text explains unfamiliar terms such as BMI category.
- Required-field errors are specific and appear near the field.
- Avoid implying that demographic features determine medical outcomes.

---

## 9.5 Home Dashboard

The right-hand reference concept is the primary composition.

### Content order

1. Personal greeting header.
2. Current-stress hero.
3. Today's vitals.
4. Personal insight.
5. Primary action cards.
6. Bottom navigation.

### A. Greeting header

- Time-sensitive greeting: “Good morning,” from `06:00–11:59`, “Good afternoon,” from `12:00–17:59`, and “Good night,” from `18:00–05:59`.
- User name uses the page-greeting style.
- Supporting copy changes with the visual period: “A calmer, healthier day starts here.” during day mode and “Rest well. A better tomorrow awaits.” during night mode.
- Profile avatar at top-right.
- The matching scenic illustration fills the header behind the content, with a semantic scrim preserving text contrast.

### B. Current-stress hero

- Large teal/green gradient card, `24 dp` radius.
- Top-left label: “Current stress”.
- Status immediately below: Normal, Elevated, High stress, or Waiting for data.
- Primary value: `0–100`, with `/100` in secondary text.
- A horizontal progress bar or compact semicircular gauge communicates magnitude.
- Right side contains a leaf/shield illustration and supportive interpretation.
- Include a “Live”, “Recent”, or “Last updated …” status.
- If the reading is outside the trained range, show an info indicator and plain-language note.
- If simulated, label it “Demo reading”; never label it live.

Suggested supportive copy by state:

| State | Heading | Supporting copy |
|---|---|---|
| Rest | “Your body appears settled” | “Recent signals suggest a restful state.” |
| Normal | “You're doing well” | “Your body signals suggest a balanced state right now.” |
| Elevated | “Take a moment” | “Your recent signals are a little elevated. A short reset may help.” |
| High | “Your stress signals are high” | “Consider pausing and trying a calming activity.” |
| Unknown | “Waiting for a reading” | “Keep your watch connected while StressGuard collects enough data.” |

### C. Today's vitals

- Section title at left, “See all” action at right.
- Three cards: Heart rate, Steps, Sleep.
- Heart-rate card shows latest bpm and a sparkline.
- Steps card shows current-day steps and compact activity bars.
- Sleep card shows hours/minutes and compact sleep bars.
- Missing values use `--` plus a useful source/permission explanation, not a fake zero.
- Each card is tappable and opens its detail page.

### D. Personal insight

- Violet gradient card.
- Sparkle/insight icon in a circular badge.
- Short title: “Your personal insight”.
- Maximum two or three lines of useful, specific copy.
- Chevron indicates navigation when details are available.
- Advice must be derived from real data or clearly framed as general guidance.

### E. Primary actions

- Two-column row when space allows:
  - “Workout mode” — mint/teal treatment and running icon.
  - “Talk to AI Assistant” — violet treatment and chat icon.
- At narrow widths, stack vertically.
- Workout subtitle: “Track your exercise”.
- Assistant subtitle: “Get insights & support”.

### Home scrolling

- Header and cards scroll as one surface.
- Bottom navigation remains fixed.
- Add bottom content padding so the final card is never obscured by navigation.
- Do not duplicate the full Trends chart on Home. A compact current-state visualization is enough.

---

## 9.6 Trends

The center reference concept defines this page. The Trends bottom-navigation item must be selected.

### Content order

1. Compact brand/page header.
2. Summary metric cards.
3. Stress chart.
4. Key drivers.
5. Workout-mode action.
6. Bottom navigation.

### A. Summary cards

Three equal cards:

- Stress score and state.
- Recovery/wellness score if supported by real implemented data; otherwise replace it with “Readings” or omit it.
- Latest heart rate.

Do not display a calculated Recovery score unless the calculation is explicitly defined and implemented.

### B. Stress chart

- Card title: “Stress level”.
- Time-range selector: Today, 7 days, or another implemented range.
- Today chart x-axis: time of day.
- Weekly chart x-axis: calendar day labels.
- Y-axis states: Rest, Normal, Elevated, High.
- Use a smooth line only when interpolation is truthful; never bridge long missing-data gaps as though readings existed.
- Line color transitions through blue, green, amber, and red according to stress band.
- Area fill uses a restrained transparent gradient.
- Selected point shows a marker, vertical guide, tooltip, score, and label.
- Legend includes both color and text.
- Provide a text summary for accessibility.

### C. Insufficient data

- Zero days: “No readings stored yet.”
- One day: “A trend needs readings from at least two days.”
- Include watch/permission guidance when the absence has a known cause.
- Do not draw a misleading flat line from insufficient data.

### D. Key drivers

Rows may include:

- Sleep.
- Heart rate.
- Activity/steps.
- Workout.

Each row contains icon, label, proportional bar, observed value, and interpretation. Interpretations must be model/data-backed. Do not imply causal certainty; use phrases such as “associated with” or “may have contributed”.

### E. Workout action

- Full-width analytical blue card.
- Running icon badge.
- Title and short subtitle.
- Chevron at right.

---

## 9.7 Heart Rate Detail

### Layout

1. Back header and title.
2. Latest heart-rate hero.
3. Source and freshness status.
4. Today chart.
5. Summary cards: minimum, maximum, average, and resting value when available.
6. Context/educational note.

### Rules

- Use the heart/coral semantic color.
- Label units as `bpm`.
- Do not diagnose tachycardia or other conditions.
- Exercise readings should be visibly distinguished from rest when workout data is available.
- Missing permission, watch disconnect, and stale readings need distinct messages.

---

## 9.8 Steps Detail

### Layout

1. Back header and title.
2. Today's steps hero.
3. Goal progress ring/bar.
4. Seven-day activity chart.
5. Average and best-day summaries.
6. Data-source/status note.

### Rules

- Use blue/cyan semantic color.
- Format large numbers with separators.
- Make it clear that the visible value is the count since local midnight.
- Never show a negative step delta.
- Explain when Health Connect is supplying fallback/corrected data.

---

## 9.9 Sleep Detail

### Layout

1. Back header and title.
2. Last sleep duration hero.
3. Goal comparison.
4. Recent sleep-duration chart.
5. Stage breakdown only when actual stage data is available.
6. Health Connect source and permission state.

### Rules

- Use violet semantic color.
- Format duration as hours and minutes where possible.
- Do not fabricate sleep stages.
- If the model uses an assumed sleep value, label it clearly as an estimate/default rather than measured sleep.

---

## 9.10 Workout Mode

### Pre-session

- Explain that workout mode tracks exercise and pauses stress interpretation so exercise-related heart-rate increases are not mistaken for stress.
- Duration selector or clear default duration.
- Primary “Start workout” action.

### Active session

- Large elapsed-time value.
- Current heart rate.
- Session step delta.
- Active/paused state.
- Pause/resume and finish controls.
- Persistent banner: “Stress analysis paused during workout.”

### Completed session

- Duration, heart-rate range/average, and steps.
- Supportive completion message.
- Return-to-dashboard action.

### Safety

- Ending the workout is a consequential action and should ask for confirmation if it would discard an active session.
- Do not use high-stress red for ordinary exercise intensity.

---

## 9.11 AI Assistant

### Structure

1. Header with assistant title and privacy/status action.
2. Optional contextual intro card.
3. Message history.
4. Suggested prompts when the conversation is empty.
5. Fixed composer above the keyboard/system inset.

### Message styling

- User messages: brand teal bubble, aligned right.
- Assistant messages: `surface_2`, aligned left.
- Safety/network fallback: amber-tinted card, clearly labelled.
- Maximum bubble width: approximately 82% of content width.
- Timestamps are low emphasis and shown only when useful.

### Composer

- Rounded field, minimum `52 dp` high.
- Visible hint such as “Share what's on your mind”.
- Send control has a `48 dp` touch target.
- Multi-line input expands to a sensible maximum, then scrolls internally.

### Safety and errors

- The assistant must never claim to diagnose or replace professional care.
- Crisis-related responses must be visually distinct, direct, and provide appropriate emergency guidance.
- Network failure must preserve the user's typed message and offer retry.
- Loading uses a subtle typing indicator; do not block navigation.

---

## 9.12 Health Checklist

### Layout

1. Back header and title.
2. Short explanation of purpose and privacy.
3. Clearly grouped checklist rows.
4. Optional explanatory helper text.
5. Sticky or bottom primary action that remains above system navigation.

### Rules

- Checkbox rows have at least `48 dp` height.
- The entire row is tappable.
- Selection is not communicated by color alone.
- Avoid alarming visual treatment for ordinary risk factors.
- Result language remains educational and non-diagnostic.

---

## 9.13 High-Stress Alert and Check-In

### Visual treatment

- Use controlled coral/red accents on a dark surface, not a fully red flashing screen.
- Alert icon and “Elevated stress signal detected” heading.
- Score and state that caused the alert.
- Short explanation such as “Several recent readings were high.”
- Preserve the triggering reading; later normal readings must not replace it while the alert is open.

### Actions

Recommended order:

1. “Start breathing” or “Take a short break”.
2. “Check in”.
3. “Talk to Assistant”.
4. “Mute alerts”.

### Feedback

- Ask whether the user felt stressed.
- Optional severity scale.
- Make dismissal possible without forcing feedback.
- Confirmation copy should be brief and supportive.

---

## 9.14 Mute Alerts

- Present as a modal sheet or focused page.
- Explain that sensing continues while alerts are muted.
- Offer explicit durations such as 1 hour, until tomorrow, or a documented custom duration.
- Show the currently selected duration.
- Provide an obvious way to unmute later in Settings.

---

## 9.15 Settings and Account

### Sections

1. Profile.
2. Watch connection.
3. Health Connect and permissions.
4. Notification and alert preferences.
5. Step and sleep goals.
6. Privacy/data synchronization.
7. About/model information.
8. Account actions.

### Row design

- `56–64 dp` minimum height.
- Leading vector icon, title, optional summary, trailing chevron/switch.
- Dividers or spacing groups, not both excessively.
- Switches communicate state with text where ambiguity is possible.

### Synchronization

Show:

- Current state: synced, pending, offline, or failed.
- Last successful sync time.
- Pending record count when available.
- Manual retry action.

### Sign out

- Place at the end of the account section.
- Use danger text but not an oversized danger-filled button.
- Confirmation must clearly explain that local history is removed.
- If records are pending upload, show the exact pending count and recommend syncing first.
- The dialog must distinguish “removed from this device” from “deleted from the server”.

---

## 10. System and Data States

Every relevant screen must explicitly support the following states.

### 10.1 Loading

- Use skeleton cards that match final dimensions for dashboard content.
- Use a compact progress indicator for form/network actions.
- Avoid indefinite full-screen spinners after the initial launch.

### 10.2 Watch disconnected

- Show a clearly labelled neutral status chip.
- Preserve the last known reading but mark it as stale.
- Provide a “Reconnect” or setup/help action where technically possible.
- Do not replace valid historical data with zeros.

### 10.3 Waiting for initial data

- Use `--` for unknown values.
- Explain what is being collected.
- Provide expected user action, such as wearing the watch correctly.

### 10.4 Stale data

- Show “Last updated …”.
- Remove the “Live” label.
- Do not animate the gauge as though a new reading arrived.

### 10.5 Offline

- Explain that stress prediction continues locally.
- Mark synchronization as pending.
- Avoid presenting offline operation as a failure unless the requested action requires the network.

### 10.6 Permission denied

- Name the missing permission and its benefit.
- Provide a direct settings action when Android permits it.
- Respect permanent denial; do not repeatedly interrupt the user.

### 10.7 Model error or invalid reading

- Do not show a fabricated score.
- Display a neutral error state with retry/wait guidance.
- Technical details belong in logs, not normal user copy.

### 10.8 Outside trained range

- Keep the result visually available but show an info marker.
- Suggested copy: “This estimate uses values outside the model's usual training range.”
- Do not imply the normal accuracy figure applies to that reading.

### 10.9 Empty history

- Distinguish no readings, one day of readings, failed restoration, and offline restoration.
- Never tell the user simply to “wait three days” when a sync or account issue may be responsible.

---

## 11. Charts and Data Visualization

- Charts always include a title and timeframe.
- Axes and labels must remain readable against the dark surface.
- Grid lines use low-contrast `border_subtle` and never compete with data.
- Use a minimum `2 dp` data line.
- Selected points use a visible marker and tooltip.
- Do not use smoothing that creates values that were never measured.
- Show gaps for missing time intervals.
- Use consistent metric colors across every page.
- Provide a textual summary for screen readers and users who cannot interpret the chart.
- Do not rely solely on red/green discrimination.

---

## 12. Motion and Feedback

Motion should feel quiet and reassuring.

| Interaction | Duration | Behavior |
|---|---:|---|
| Button press | 80–120 ms | Opacity/elevation response |
| Card/state transition | 180–240 ms | Fade and small position change |
| Page content entrance | 220–300 ms | Subtle fade; no large slide |
| Gauge update | 350–500 ms | Smooth value interpolation |
| Bottom-nav selection | 180–220 ms | Tint and pill transition |

Rules:

- Never pulse continuously to attract attention.
- Do not animate layout bounds in a way that shifts surrounding content.
- New sensor readings may animate the value once.
- Respect Android's reduced-motion/animation-scale settings.
- Haptic feedback should be reserved for meaningful confirmation and alerts, not every card tap.

---

## 13. Accessibility

- Normal text contrast: at least `4.5:1`.
- Large text and meaningful non-text controls: at least `3:1`.
- Touch targets: at least `48 × 48 dp` on Android.
- Support TalkBack with meaningful content descriptions and logical focus order.
- Decorative icons and artwork must not be announced.
- Icon-only controls must have clear accessible names.
- State must never be communicated by color alone.
- Charts require text summaries.
- Dynamic text must not overlap, truncate critical meaning, or hide actions.
- Keyboard focus and the software keyboard must not obscure the Assistant composer or form errors.
- Authentication must support paste and password managers.
- Error text should explain how to recover, not only what failed.

---

## 14. Responsive Rules

### Compact phone

- Reduce decorative artwork before reducing text size.
- Stack dual action cards vertically when labels no longer fit.
- Allow vitals to wrap or scroll horizontally.
- Never shrink touch targets.

### Large phone/tablet

- Center content within a maximum `600 dp` column for single-column screens.
- Dashboard may use a two-column layout only when information hierarchy remains clear.
- Charts may expand horizontally but should not become excessively tall.

### Landscape

- Keep critical score and action above the fold where possible.
- Use two columns for hero/supporting content.
- Ensure bottom/system navigation does not cover actions.

---

## 15. Android Implementation Rules

- Use Material 3 components where they match the design, with project-specific theme overrides.
- All colors, dimensions, typography, and shapes must come from named resources/tokens.
- Do not hardcode hex colors directly inside layouts or Activities.
- Use vector drawables for standard icons.
- Use adaptive launcher icons with foreground, background, and monochrome layers.
- Respect `WindowInsets` on every screen.
- Reuse shared card, button, field, header, chip, and bottom-navigation styles.
- Preserve offline-first behavior; UI polish must not add network dependencies to prediction.
- Never block rendering while waiting for optional backend data.
- Keep stress labels and semantic colors in one shared mapping.
- Keep units and formatting in reusable formatters.

---

## 16. Content Style

### Voice

- Calm.
- Direct.
- Supportive.
- Non-judgmental.
- Honest about uncertainty.

### Preferred patterns

- “Your recent signals suggest…”
- “You may benefit from…”
- “No reading available yet.”
- “Last updated 4 minutes ago.”
- “Stress analysis is paused during your workout.”

### Avoid

- Medical certainty.
- Blame or shame.
- Excessive exclamation marks.
- Vague errors such as “Something went wrong” without recovery guidance.
- Technical model terminology on primary screens.

---

## 17. Page-Level Quality Checklist

Every page must pass these checks before being considered complete:

- Correct page is selected in bottom navigation.
- Status-bar and navigation-bar insets are respected.
- No important text is smaller than `12 sp`.
- No control has a touch target below `48 dp`.
- Loading, empty, error, offline, and permission states are implemented where relevant.
- Long names and 200% font scaling do not break the layout.
- Colors come from semantic tokens.
- Icons use one family and consistent sizes.
- Critical state is communicated by text as well as color.
- Screen works on a small phone and in landscape.
- Back navigation behaves predictably.
- Content is not hidden behind bottom navigation or the keyboard.
- Health copy does not claim diagnosis.
- Data freshness/source is visible where it affects interpretation.
- TalkBack focus order matches visual order.

---

## 18. Acceptance Criteria for the Final UI

The implementation matches this specification when:

1. The application visibly belongs to one coherent, time-aware wellness design system with complete day and night modes.
2. Home uses the greeting → current stress → check-in → vitals → insight → actions hierarchy.
3. Trends owns the detailed chart and key-driver analysis instead of duplicating them on Home.
4. Heart rate, steps, sleep, and stress use consistent semantic colors everywhere.
5. Current, stale, missing, simulated, offline, and out-of-range data are visually distinguishable.
6. All core pages use shared spacing, typography, radii, borders, and components.
7. Authentication and profile screens feel like the same product as the dashboard.
8. Alerts are noticeable without being frightening.
9. Charts remain understandable without relying only on color.
10. The UI remains usable with large text, TalkBack, narrow screens, and system insets.
11. No visual change harms stress prediction, watch communication, offline operation, authentication, synchronization, or alerts.
12. Screens are tested with real data, missing data, long values, and disconnected-device states—not only ideal mock data.
13. Day and night modes preserve identical data and actions, use correct system-bar icon contrast, and switch at the documented local-time boundaries.
14. The Home header displays the matching scenic artwork and greeting without announcing decorative imagery to TalkBack.

---

## 19. Deliberate Differences from the Reference Image

The following differences are intentional quality corrections:

- Trends highlights the Trends navigation item.
- The Home screen does not duplicate the full detailed Trends chart.
- Mood faces use controlled vector assets rather than system emoji.
- A Recovery score is shown only if a real implemented formula exists.
- Decorative mountain/sun artwork is not presented as weather information.
- “Live” is shown only for genuinely current watch data.
- Missing measurements use an unknown state rather than zero.
- Charts show gaps instead of inventing measurements.
- Stress language is framed as an estimate and not a diagnosis.

These corrections preserve the reference's visual identity while improving technical honesty, accessibility, and product consistency.
