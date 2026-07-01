# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Aide** — a native Android (Kotlin, Jetpack Compose) personal digital assistant app, targeting a single device (Galaxy S24 Ultra). It registers as the system Assistant so the device's assist shortcut shows a lightweight overlay (like Gemini) instead of opening an Activity, captures voice/photo/conversation input across 4 modes, and sends it to a user-configured n8n webhook for AI processing. Results that take a while arrive via notification.

The full architecture and phased build plan live in `~/.claude/plans/scalable-juggling-dijkstra.md`.

## Build / lint / test

This is a single-module Gradle project (`:app`). Use the wrapper, not a system `gradle`/`adb` — `adb` is at `~/Library/Android/sdk/platform-tools/adb` and isn't on `PATH` in this environment.

```bash
./gradlew :app:assembleDebug          # build a debug APK
./gradlew :app:installDebug           # build + install on a connected/USB-debugging device
./gradlew test                        # unit tests (JVM)
./gradlew :app:testDebugUnitTest --tests "com.yshah.aide.SomeTest"   # single unit test
./gradlew connectedDebugAndroidTest   # instrumented tests (requires a connected device)
./gradlew lint                        # Android Lint
```

Gradle wrapper is pinned to **Gradle 9.6.1** (required by AGP 9.x). First invocation of `./gradlew` downloads it fresh — no cached-compatible Gradle exists locally otherwise.

`local.properties` (gitignored) points `sdk.dir` at `~/Library/Android/sdk`, which has platforms up to `android-36.1` (Android 16) and build-tools up to `37.0.0`. `compileSdk`/`targetSdk` are pinned to 36 (the highest fully-installed platform) — bump if a newer platform is installed.

### Gradle daemon memory

`gradle.properties` sets `org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m` — the Gradle daemon's default heap in this environment (512 MiB) is too small and D8/dexing will OOM without this.

### Known version-compatibility gotchas (hit and fixed during scaffolding — don't reintroduce)

- **AGP 9+ has Kotlin compilation built in.** Do not apply `org.jetbrains.kotlin.android` — it now conflicts. Only the standalone compiler plugins (`org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`) are applied separately. See https://kotl.in/gradle/agp-built-in-kotlin.
- **Hilt Gradle plugin requires >= 2.59** to work with AGP 9's new DSL (`hilt = "2.59.2"` in the version catalog). Older Hilt throws `IllegalStateException: Android BaseExtension not found`.
- **`kotlin-metadata-jvm` version must match the Kotlin compiler version** or KSP/Hilt annotation processing fails with `Provided Metadata instance has version X, while maximum supported version is Y`. `app/build.gradle.kts` forces `org.jetbrains.kotlin:kotlin-metadata-jvm` to the catalog's `kotlin` version via `resolutionStrategy`.
- All exact library versions in `gradle/libs.versions.toml` were cross-checked against `./gradlew :app:lintDebug`'s live `GradleDependency` check on 2026-07-01 (it queries actual Maven metadata — more reliable than web search). Re-run lint before bumping further.
- **Check each runtime permission independently, not just one as a proxy for "all granted."** A user who granted RECORD_AUDIO before POST_NOTIFICATIONS existed in the app would never be prompted for it, since the old code only gated the multi-permission request on RECORD_AUDIO's grant state. `AideOverlayScreen` now tracks each permission separately and requests exactly the missing ones.
- **`POST_NOTIFICATIONS` denial shows as `importance=NONE` in `adb shell dumpsys notification`**, and `numEnqueuedByApp` vs `numPostedByApp` in that same dump tells you whether your code actually called `notify()` (enqueued) vs whether the system displayed it (posted) — a fast way to tell "my code has a bug" from "permission is blocked" during on-device debugging.
- **`SpeechRecognizer.createOnDeviceSpeechRecognizer()` is broken on this device for our use case** — confirmed via `adb logcat` that the on-device ("Soda") engine processes a full ~5s window and reports an empty result even with clear speech present (`#onStartOfSpeech` fires, immediately followed by `MIC_END_OF_DATA` and an empty hypothesis). `SpeechRecognizerCaptureController` now always uses the plain `SpeechRecognizer.createSpeechRecognizer(context)`, which resolves to Google's bundled recognition service and works reliably, but likely sends audio to Google's servers rather than staying on-device — a real privacy/connectivity trade-off from the original on-device-preferred design. Don't switch back to `createOnDeviceSpeechRecognizer()` without re-verifying against a real device first.
- **n8n AI Agent nodes with streaming enabled return newline-delimited JSON events**, not one JSON object — `{"type":"begin"/"item"/"end", "content": ..., "metadata": {...}}` per token/node, and the actual answer is the *last* "item" event's `content`, which is itself often JSON-encoded again (e.g. `content: "{\"output\": \"...\"}"`). `WebhookClient.parseResponseBody` handles single-JSON, this NDJSON-stream shape, and a handful of common field names (`RESPONSE_TEXT_KEYS`) — don't replace this with a naive single `decodeFromString` call.
- **n8n webhook responses can be empty or non-JSON** even with a "Respond to Webhook" node configured — confirmed against a real test webhook when the payload's `type` value didn't match any branch in the workflow's routing logic, so execution never reached the response node. `WebhookClient`'s `AideWebhookApi` therefore returns raw `Response<ResponseBody>`, not a typed body — `RetrofitWebhookClient.executeCall` parses the body leniently and treats any 2xx as `Success` even if the body is empty/unparseable. Don't revert to a typed Retrofit response converter for this API.
- **compileSdk is capped at 36** because the locally installed Android SDK only has platforms up to `android-36.1` (no 37). Several newer library releases (`androidx.core:core-ktx` 1.19.0+, `androidx.lifecycle:*` 2.11.0+, `androidx.hilt:hilt-navigation-compose` 1.4.0+) require compileSdk 37 and were deliberately pinned to older versions to stay buildable — bump both together once platform 37 is installed (`sdkmanager "platforms;android-37"`, not attempted yet since no `cmdline-tools`/`sdkmanager` is installed locally either).

## Architecture

```
app/src/main/java/com/yshah/aide/
├── AideApplication.kt          # @HiltAndroidApp, creates the notification channel
├── MainActivity.kt              # primary overlay entry point (see Samsung side-button finding below)
├── assistant/                   # VoiceInteractionService/Session (retained infra) + AssistantMode enum (TASK/NOTE/CONVO)
├── capture/                     # SpeechCaptureController (SpeechRecognizer-backed), TtsController (Android TextToSpeech)
├── convo/                       # ConvoStateMachine — mode 4's listen/send/speak turn-taking loop
├── network/                     # WebhookClient, Retrofit API, auth interceptor, lenient NDJSON-aware response parsing
├── settings/                    # SecureSettingsStore (DataStore+Tink), SettingsScreen/Activity/ViewModel
├── prefs/                       # ModePreferences (last-selected mode, DataStore)
├── webhook/                     # WebhookForegroundService (background delivery + notification + history write)
├── data/                        # Room: InteractionEntity/Dao/AideDatabase (interaction history)
├── history/                     # HistoryActivity/Screen/ViewModel — reachable from Settings and notification tap
├── ui/                          # overlay screens (AideOverlayScreen/ViewModel), theme, shared components
└── di/                          # Hilt modules
```

**Status: functionally complete for Task/Note/Convo modes**, verified end-to-end on-device (S24 Ultra) against a real n8n webhook — scaffold, assistant-role registration (retained but not primary — see below), mode-switcher overlay with persistence, Settings + secure webhook config + networking core, Task/Note voice capture with background delivery + notifications + history, and Convo mode with TTS turn-taking. Camera mode was implemented then removed — see the plan file's "Camera mode deferred" section before re-adding it. Convo mode's first turn works reliably; multi-turn continuity has known issues likely needing n8n-side (not just app-side) tuning — see the plan's Convo mode section.

**Samsung side-button finding**: the assist-role/`VoiceInteractionSession` mechanism registers correctly but Samsung's One UI side-button gesture launches `MainActivity` directly rather than invoking the session — see the plan file's "On-device finding" section before touching `assistant/` or the overlay-invocation path. `MainActivity` (not the session) is the actual, primary overlay UI, and it `finish()`es in `onStop()` so backgrounding by any means (not just explicit dismiss) tears down capture/convo state cleanly.
