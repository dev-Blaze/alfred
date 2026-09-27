<div align="center">
  <img src="icon/icon.png" alt="Alfred icon" width="160" />
  <h1>Alfred</h1>
  <p><strong>A personal Android voice assistant that talks to your n8n workflows.</strong></p>
</div>

Alfred replaces your phone's assistant shortcut with a lightweight overlay — long-press the side button, speak, and what you said is delivered to a self-hosted [n8n](https://n8n.io) webhook where an AI agent (or any workflow) does the actual work. Built with Kotlin and Jetpack Compose for a Samsung Galaxy S24 Ultra; requires Android 14+.

The app provides generic capture, clarification messages, and results. Calendar integration and functionality belong in your n8n workflow; no calendar or other n8n integration is implemented here. See [0.3.1 release notes](RELEASE_NOTES_0.3.1.md).

## Modes

| Mode | How it works |
|------|--------------|
| **Task** | Speak a task; capture auto-stops when you pause. Sent as `type: "task"`, response arrives as a notification. |
| **Note** | Open-ended dictation with a manual stop button. Sent as `type: "note"`. |
| **Convo** | Back-and-forth conversation — your question is sent as `type: "convo"` and the workflow's reply is spoken aloud via TTS, then Alfred listens again. |

The overlay remembers your last-used mode. Task and Note requests are persisted in Room before acceptance, then delivered by network-constrained WorkManager jobs with periodic recovery. Results appear in History and, when permitted, notifications. Interrupted recognition retains text for review instead of silently submitting incomplete speech.

## Setup

1. Install the APK from [Releases](../../releases) (sideload; Android 14+).
2. Open Alfred → gear icon → set your **n8n webhook URL** and auth (Bearer token, Basic, or a custom header — matching n8n's Header Auth credential). Credentials are encrypted at rest via Google Tink with an Android Keystore master key.
3. **Test connection** checks the capability response below using the current Settings draft. A legacy backend can still be saved when capabilities are unverified.
4. Set Alfred as your assistant: *Settings → Apps → Default apps → Digital assistant app* (Samsung: *Settings → Advanced features → Side button → Press and hold → Digital assistant app*), or via adb:
   ```bash
   adb shell cmd role add-role-holder android.app.role.ASSISTANT com.yshah.alfred
   ```
5. In Alfred's Settings, request the **battery optimization exemption** so background delivery survives aggressive OEM power management.

## The webhook contract

Alfred POSTs JSON to your webhook:

```json
{
  "type": "task",
  "text": "the transcribed speech",
  "timestamp": "2026-07-01T21:00:00Z",
  "sessionId": "uuid-per-capture",
  "capturedAt": 1782939600000,
  "timeZone": "Europe/London",
  "source": "phone",
  "requestId": "uuid-per-capture",
  "conversationId": null,
  "schemaVersion": 1
}
```

Route on `type`: `task`, `note`, `convo`, or `ping`. Existing `text`, ISO-8601 `timestamp`, and `sessionId` fields remain. Added metadata carries capture time in epoch milliseconds, IANA timezone, source (`phone`/`watch`), and schema version. Task/Note preserve their request ID and capture metadata across retries. Convo uses a new `requestId` per turn with stable `sessionId`/`conversationId` per conversation.

Prefer a response such as `{"status":"completed","responseText":"Done"}`:

| Response status | Meaning |
|---|---|
| `accepted` | Server accepted; completion is not confirmed. |
| `completed` | Server reports completion. |
| `failed` / `error` | Server reports failure. `success: false` or a nonempty/non-false `error` also indicates failure. |
| `needs_confirmation` | Show the server's clarification/confirmation message for review; no automatic approval or domain-specific action. |
| Unrecognized status | Outcome unknown. |

HTTP 2xx alone does not prove completion. Legacy formats remain readable:

- A JSON object or single-element array containing a `responseText`, `output`, `text`, `response`, `message`, or `reply` string field (n8n AI Agent nodes commonly produce `[{"output": "..."}]`)
- n8n's **streaming** NDJSON event format (the final `item` event's `content` is used)
- Plain text or JSON strings. Status-less legacy replies are labeled delivered, with completion unconfirmed; Convo can speak them with a legacy-outcome warning.
- An empty 2xx body means accepted, not completed. NDJSON error events are treated as failure.

The Settings probe sends the same metadata envelope with `type: "ping"`, `text: ""`, and `sessionId: "test"`. Verification requires HTTP 2xx and this JSON object (extra capabilities are allowed):

```json
{"type":"pong","schemaVersion":1,"capabilities":["task","note","convo"]}
```

Optional pong `status` must be `accepted` or `completed`, with no failure indicator. A plain/empty legacy 2xx response leaves capabilities unverified; it does not block saving valid settings. This documents the client contract, not a supplied n8n workflow.

Convo mode uses a short (~20s) timeout since you're actively waiting for a spoken reply; Task/Note allow up to 5 minutes in the background.

## Delivery, watch compatibility, and security

- Timeouts, connection failures, or interruption after a delivery is claimed become **uncertain**. They are not automatically replayed. Phone History offers explicit retry for uncertain, HTTP-error, unknown, or failed queued requests, requiring acknowledgement that the server may already have executed them. Stable IDs support backend deduplication but do not guarantee exactly-once execution.
- Watch captures use `/alfred/capture/{requestId}` and are durably deduplicated before deletion. Legacy `sessionId` and epoch-millisecond `timestamp` are accepted; timestamp-only captures default to UTC. Modern captures include `capturedAt` and `timeZone`. Reusing an ID with different capture data is rejected.
- Results at `/alfred/result/{requestId}` contain `requestId`, `status`, `message`, and increasing `resultRevision`. Watch statuses are `accepted`, `success` (only confirmed completion), `http_error`, and `uncertain` (other outcomes needing review). Newer revisions order explicit retries; old/equal revisions cannot regress state, and success is retained. The companion also accepts legacy results without revisions conservatively. Result publication retries do not resend the webhook.
- Phone and watch require the same application ID (`com.yshah.alfred`) and signing certificate. Deploy matched release builds using the same private keystore, or debug builds using the same debug certificate. Changing certificates requires uninstalling the old build and loses local data.
- Webhooks require HTTPS with validated authentication headers and one URL/auth snapshot per request. Redirects and automatic connection retries are disabled; response bodies are limited to 1 MiB. Unreadable encrypted credentials require re-entry rather than silently sending unauthenticated.
- Backup and device transfer exclude webhook settings/key material and the entire delivery/history database, including journal files. **History is excluded too** because it shares the queue database; restoring queued mutations must not replay them. Watch drafts/history are also excluded from backup/transfer. Same-signature in-place upgrades differ from device migration.

## Building

```bash
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # minified, signed release (needs your own keystore.properties)
```

Release signing reads `keystore.properties` (see `app/build.gradle.kts`); without it the release build is unsigned. See `CLAUDE.md` for toolchain notes and hard-won platform gotchas (Samsung side-button behavior, broken on-device speech recognition, n8n response-shape quirks).

## Known limitations

- **Camera mode** was prototyped and deferred — n8n received multipart fields as separate binary files; needs the part-encoding issue resolved first.
- **Convo multi-turn continuity** depends on your workflow maintaining session state (correlate on `sessionId`/`conversationId`).
- **Speech-to-text** uses the system's Google recognizer, which may process audio off-device — the on-device-only recognizer proved unreliable on the S24 Ultra.

## License

Personal project, provided as-is with no license granted for redistribution — but feel free to read and learn from it.
