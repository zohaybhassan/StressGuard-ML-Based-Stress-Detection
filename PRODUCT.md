# StressGuard Product Context

<!-- impeccable:product-schema 1 -->

> This context is inferred from the user's explicit brief, the supplied final UI reference, `design.md`, and the existing repository. No product interview was available during this implementation pass.

## Platform

Native Android phone application with a companion Wear OS application. This redesign targets the phone `app` module only.

## Users

People who want a calm, glanceable view of their current stress estimate and the personal signals that inform it. Many users may open the app while already stressed, so the interface must remain legible, supportive, and low-friction.

## Product Purpose

StressGuard turns locally available wellness signals—such as watch heart rate and steps, sleep, profile information, and recent context—into an understandable stress estimate. It helps users notice patterns, review trends, receive timely alerts, and take a small supportive action.

## Positioning

StressGuard is a privacy-conscious wellness companion, not a medical diagnostic product. It emphasizes transparent, on-device stress estimation, clear data freshness, and practical next actions rather than clinical claims or competitive fitness scoring.

## Operating Context

- Short, repeated phone glances throughout the day.
- A paired Wear OS watch may stream heart rate and steps in the background.
- Some data may be stale, unavailable, estimated, or awaiting permission.
- The app must continue to communicate useful states when offline or when the watch is disconnected.
- The dark interface is designed for comfortable use in both indoor and low-light environments.

## Existing Capabilities to Preserve

- Email and Google authentication through the configured Supabase project.
- Profile setup and health checklist flows.
- Wear OS connection, passive vitals collection, and phone/watch state transfer.
- Health Connect sleep access and its permission/error states.
- On-device ONNX stress inference and its current input pipeline.
- Local Room history, trends, sync/restore behavior, and logout semantics.
- High-stress alerts, alert feedback, muting, and settings.
- Workout mode and workout history.
- Assistant conversations and crisis-safe backend behavior.
- Existing navigation, view IDs, accessibility hooks, and lifecycle behavior.

## Constraints

- This implementation is a visual redesign only. Business logic, data contracts, prediction behavior, storage, and backend communication must not change.
- Do not invent unavailable metrics such as a recovery score, weather, or unsupported physiological measurements.
- A missing or stale value must be shown honestly rather than replaced with plausible-looking sample data.
- The app must not imply medical diagnosis or guaranteed health outcomes.
- The phone UI must remain usable without the watch, network access, or optional health permissions.

## Brand Commitments

- Product name: StressGuard.
- Visual direction: the supplied final dark mobile UI reference and the detailed `design.md` specification.
- Personality: calm, trustworthy, contemporary, private, and quietly encouraging.
- Primary visual language: near-black teal/navy canvases, layered cool surfaces, mint-teal emphasis, restrained cyan/purple/rose metric accents, rounded cards, and high-contrast white typography.
- Icons are real vector assets; emoji are not used as interface icons.

## Evidence on Hand

- The existing functional Android and Wear OS source code.
- The repository README and current resource system.
- The user's supplied three-screen final UI reference.
- `design.md`, which records the approved visual system and screen-by-screen requirements.
- A connected physical Android phone for build, installation, and screenshot verification.

## Product Principles

1. Show the current state first, then explain it, then offer one useful action.
2. Keep the stress estimate visually dominant without hiding its source or freshness.
3. Use supportive language and avoid alarming or judgmental phrasing.
4. Preserve privacy and local-first behavior as visible product strengths.
5. Prefer honest empty, loading, stale, and disconnected states over decorative placeholders.
6. Keep navigation and action placement predictable across every screen.

## Accessibility and Inclusion

- Minimum 48dp touch targets for actionable controls.
- Text and meaningful graphics meet WCAG AA contrast where technically applicable.
- Dynamic text must remain readable without clipping; layouts should tolerate at least 1.3× font scale during device verification.
- Icon-only actions require content descriptions.
- Color is never the sole carrier of stress status or connection state.
- System Back, edge-to-edge insets, keyboard insets, and reduced-motion expectations are respected.
