# Alfred 0.3.1

## Reliability and delivery

- Task/Note requests are accepted only after durable Room persistence, with WorkManager network gating and recovery. Watch captures are deduplicated by immutable request ID before their DataItems are deleted.
- In-flight interruption, timeout, and connection failure produce an uncertain outcome, without automatic webhook replay. Retry in History requires explicit acknowledgement of possible duplicate execution. Result publication retries are independent of webhook execution.
- Incomplete speech is retained for review. Capture/TTS cancellation and stale-callback handling protect conversation turn-taking; playback has bounded waits, and replies are retained in History.
- Watch results include increasing `resultRevision` values so stale acknowledgements/errors cannot overwrite newer retry results. Legacy capture IDs/timestamps remain accepted; timestamp-only captures use UTC. The companion accepts unversioned results conservatively.

## Protocol and security

- Requests preserve legacy fields and add capture time, timezone, source, request ID, conversation ID, and schema version. Convo has per-turn request IDs and stable conversation correlation.
- Response statuses distinguish `accepted`, `completed`, `failed`/`error`, and `needs_confirmation`. Unknown statuses and legacy delivery are not confirmed completion. JSON/text/NDJSON compatibility remains; empty 2xx means accepted. Legacy Convo replies remain speakable with an outcome warning.
- Settings tests the current draft against `{"type":"pong","schemaVersion":1,"capabilities":["task","note","convo"]}`. Legacy backends can still be saved with capabilities unverified. See [README](README.md#the-webhook-contract) for the full contract.
- HTTPS and authentication validation, immutable URL/auth snapshots, disabled redirects/automatic connection retries, and 1 MiB response limits harden webhook delivery. Unavailable encrypted credentials require re-entry.
- Backup/device transfer excludes credentials and the whole delivery/history database, including journals. **History is also excluded**, preventing restored queued mutations from replaying.

Calendar integration and functionality belong in n8n. This release implements generic capture, clarification/result handling, and delivery; it does not implement calendar or other n8n integrations.

## Validation and deployment

- Passed in both repositories: `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleRelease` (including up-to-date Gradle tasks).
- Device migration was not executed. These checks do not establish on-device verification of 0.3.1.
- Deploy phone/watch APKs with matching `com.yshah.alfred` application IDs and signing certificates. Release pairs must share the private keystore; debug pairs must share the debug certificate. Switching certificates requires uninstalling the previous build and deletes local history/drafts. Backup does not migrate that history.
