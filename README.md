# fugazi

A small Android app for noticing your own patterns. It combines a habit tracker that knows *why* each habit matters with signals your phone and watch already collect, plus a blank page to write in every day.

Everything stays on the phone unless you turn on one of two optional extras: a backup to your own Google Drive, and daily reflections from Kimi with your own API key. There's no fugazi account and no fugazi server.

## What it does

**Habits, with their meta.** For each habit you write down:

- why it matters to you, and what it's for
- when it usually happens, and how often: daily, or something like "2 per week"
- **a ladder**, meaning what a lapse means at each length and what to ask yourself then. For example: "3 days: fine. 7 days: concerning. Are mornings busy? (Busy mornings / Ran out / Forgot)". When a step is reached you get a check-in with that question and your pre-written answers.

It works in the other direction too. After a week on pace, or 7, 14 or 30 days clean for a "do less" habit, it asks what's making it work. Tapping a habit done shows your own reason back to you. There are no streaks. Strength is a Loop-style score that one missed day barely moves.

**States.** A habit can also be *a state* you want to live in more, like "calm and present". You tap the days you felt it and pick what put you there (hard effort, awe, a stranger, giving, flow, no phone, good sleep, or your own list), plus a note. Days without it aren't misses; it only asks after a long stretch away.

**Marked for you.** Some habits can mark themselves using Health Connect and phone usage:

- *No phone after waking*: wake time from your watch vs. your first unlock
- *Wake on a schedule*: wake time vs. your usual (median) wake time
- *A walk*: continuous walking from step records or recorded walks

You choose the sleep source in settings: Fitbit, Galaxy Watch (Samsung Health) or any app.

**Signals.** Once a day it records screen time, late-night phone use (23:00–03:00), unlocks, wake time, steps, longest walk, and music habits (songs started vs. skipped). Late-night use rising on consecutive nights, together with higher screen time, both compared against your own recent usual, triggers a check-in.

**Thoughts.** A warm page for today, saved as you type, with earlier days along the top.

**Reflect (optional).** With a Kimi API key, a model reads your last two weeks every morning: your habits and why they matter, what the phone saw, and above all what you wrote. It writes back like a friend who read your journal. It answers your answers to yesterday's questions, goes through your writing in detail, connects today to the days before, tracks what tends to put you in your states, and leaves questions to sleep on. It keeps its own running notes between days. Model (Kimi K3, K2.6 or any other) and thinking effort are chosen in settings. The key stays on the phone.

**Google Drive backup (optional).** Mirrors the journal to a "fugazi" folder in your Drive shortly after you leave the app and every few hours. It only uploads what changed, never deletes anything in Drive, and can restore everything onto a new phone. It uses the `drive.file` scope, so it can only see files it created. To use it with your own build, create an Android OAuth client in Google Cloud for package `com.fugazi.app` and your signing key's SHA-1.

**Export.** One tap shares everything as text (`habits.jsonl`, `events.jsonl`, `signals.jsonl`, and your thoughts), ready for reading back or handing to an LLM.

## Permissions

| Permission | Why |
|---|---|
| Usage access | Screen time, late-night use, unlock times |
| Health Connect: sleep, steps, exercise | Wake time and walks |
| Notification access | Only to see media sessions for song-skip counts. Notifications aren't read. |
| Notifications | Check-ins |
| Ignore battery optimisation | So the background check keeps running. The home screen shows when it last ran. |
| Internet | Only for the optional Kimi reflections and Drive backup |
| Foreground service | So a reflection you asked for finishes after you leave the app |

## Install

Download the APK from [Releases](../../releases) and sideload it. Builds are debug-signed.

## Build

JDK 17 and the Android SDK (platform 35):

```
./gradlew :app:testDebugUnitTest   # pure-Kotlin tests for the ladder, states, auto rules, the reflection digest and sync
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Kotlin, Jetpack Compose, WorkManager, Health Connect. Data is stored as plain JSONL and Markdown files in app storage.
