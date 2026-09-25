# fugazi

A small Android app for noticing your own patterns. It combines a habit tracker that knows *why* each habit matters with signals your phone and watch already collect, plus a blank page to write in every day.

Everything stays on the phone. There's no account and no server.

## What it does

**Habits, with their meta.** For each habit you write down:

- why it matters to you, and what it's for
- when it usually happens, and how often: daily, or something like "2 per week"
- **a ladder**, meaning what a lapse means at each length and what to ask yourself then. For example: "3 days: fine. 7 days: concerning. Are mornings busy? (Busy mornings / Ran out / Forgot)". When a step is reached you get a check-in with that question and your pre-written answers.

It works in the other direction too. After a week on pace, or 7, 14 or 30 days clean for a "do less" habit, it asks what's making it work. Tapping a habit done shows your own reason back to you. There are no streaks. Strength is a Loop-style score that one missed day barely moves.

**Marked for you.** Some habits can mark themselves using Health Connect and phone usage:

- *No phone after waking*: wake time from your watch vs. your first unlock
- *Wake on a schedule*: wake time vs. your usual (median) wake time
- *A walk*: continuous walking from step records or recorded walks

You choose the sleep source in settings: Fitbit, Galaxy Watch (Samsung Health) or any app.

**Signals.** Once a day it records screen time, late-night phone use (23:00–03:00), unlocks, wake time, steps, longest walk, and music habits (songs started vs. skipped). Late-night use rising on consecutive nights, together with higher screen time, both compared against your own recent usual, triggers a check-in.

**Thoughts.** A blank page for today, saved as you type, with earlier days underneath.

**Export.** One tap shares everything as text (`habits.jsonl`, `events.jsonl`, `signals.jsonl`, and your thoughts), ready for reading back or handing to an LLM.

## Permissions

| Permission | Why |
|---|---|
| Usage access | Screen time, late-night use, unlock times |
| Health Connect: sleep, steps, exercise | Wake time and walks |
| Notification access | Only to see media sessions for song-skip counts. Notifications aren't read. |
| Notifications | Check-ins |
| Ignore battery optimisation | So the background check keeps running. The home screen shows when it last ran. |

## Install

Download the APK from [Releases](../../releases) and sideload it. Builds are debug-signed.

## Build

JDK 17 and the Android SDK (platform 35):

```
./gradlew :app:testDebugUnitTest   # pure-Kotlin tests for the ladder, auto rules, walks and radar
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Kotlin, Jetpack Compose, WorkManager, Health Connect. Data is stored as plain JSONL and Markdown files in app storage.
