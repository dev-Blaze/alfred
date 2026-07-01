<div align="center">
  <img src="icon/icon.png" alt="Alfred icon" width="160" />
  <h1>Alfred</h1>
  <p><strong>A personal Android voice assistant that talks to your n8n workflows.</strong></p>
</div>

Alfred replaces your phone's assistant shortcut with a lightweight overlay — long-press the side button, speak, and what you said is delivered to a self-hosted [n8n](https://n8n.io) webhook where an AI agent (or any workflow) does the actual work. Built with Kotlin and Jetpack Compose for a Samsung Galaxy S24 Ultra, but should behave on any Android 14+ device.

## Modes

| Mode | How it works |
|------|--------------|
| **Task** | Speak a task; capture auto-stops when you pause. Sent as `type: "task"`, response arrives as a notification. |
| **Note** | Open-ended dictation with a manual stop button. Sent as `type: "note"`. |
| **Convo** | Back-and-forth conversation — your question is sent as `type: "convo"` and the workflow's reply is spoken aloud via TTS, then Alfred listens again. |

The overlay remembers your last-used mode. Task and Note sends run through a foreground service, so you can switch apps immediately — the result lands as an expandable notification and is recorded in a history screen (tap the notification or open it from Settings).

## Setup

1. Install the APK from [Releases](../../releases) (sideload; Android 14+).
2. Open Alfred → gear icon → set your **n8n webhook URL** and auth (Bearer token, Basic, or a custom header — matching n8n's Header Auth credential). Credentials are encrypted at rest via Google Tink with an Android Keystore master key.
3. **Test connection** to confirm the workflow answers.
4. Set Alfred as your assistant: *Settings → Apps → Default apps → Digital assistant app* (Samsung: *Settings → Advanced features → Side button → Press and hold → Digital assistant app*), or via adb:
   ```bash
   adb shell cmd role add-role-holder android.app.role.ASSISTANT com.yshah.alfred
   ```
5. In Alfred's Settings, request the **battery optimization exemption** so background delivery survives aggressive OEM power management.

## The webhook contract

Alfred POSTs JSON to your webhook:

```json
{
  "type": "task | note | convo | ping",
  "text": "the transcribed speech",
  "timestamp": "2026-07-01T21:00:00Z",
  "sessionId": "uuid-per-capture"
}
```

Route on `type` in your workflow (`ping` is the Settings test-connection probe). The response is parsed leniently — any of these work:

- A JSON object or single-element array containing a `responseText`, `output`, `text`, `response`, `message`, or `reply` string field (n8n AI Agent nodes commonly produce `[{"output": "..."}]`)
- n8n's **streaming** NDJSON event format (the final `item` event's `content` is used)
- Plain text, or an empty body (any HTTP 2xx counts as delivered)

Convo mode uses a short (~20s) timeout since you're actively waiting for a spoken reply; Task/Note allow up to 5 minutes in the background.

## Building

```bash
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # minified, signed release (needs your own keystore.properties)
```

Release signing reads `keystore.properties` (see `app/build.gradle.kts`); without it the release build is unsigned. See `CLAUDE.md` for toolchain notes and hard-won platform gotchas (Samsung side-button behavior, broken on-device speech recognition, n8n response-shape quirks).

## Known limitations

- **Camera mode** was prototyped and deferred — n8n received multipart fields as separate binary files; needs the part-encoding issue resolved first.
- **Convo multi-turn continuity** depends on your workflow maintaining session state (correlate on `sessionId`); the first turn is solid.
- **Speech-to-text** uses the system's Google recognizer, which may process audio off-device — the on-device-only recognizer proved unreliable on the S24 Ultra.

## License

Personal project, provided as-is with no license granted for redistribution — but feel free to read and learn from it.
