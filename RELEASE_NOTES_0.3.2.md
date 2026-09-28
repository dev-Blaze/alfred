# Alfred 0.3.2

## Receipts, clarification, and follow-ups

- History preserves action receipts, external IDs, and validated HTTPS links. Task/Note replies and corrections queue new requests linked by `inReplyTo`, `contextToken`, and `conversationId`; retries preserve their identity and context.
- Convo speaks clarification questions and carries backend context into subsequent turns. History uses the outbound request ID for each turn; accepted/failed outcomes cannot masquerade as completed replies.
- Room migration preserves existing history and queued requests while adding follow-up and response metadata. History export includes the new fields.
- Shortened README, documented the v0.3.2 n8n contract and Calendar setup requirements, and recorded the per-phase release workflow. Calendar integrations remain in n8n.

## Verification

- `:app:testDebugUnitTest`: 33 tests, 0 failures/errors/skips; Gradle verified existing results as up-to-date.
- `:app:lint`: 0 errors, 29 warnings (SDK/dependency updates, battery exemption, resource qualifier, and version-catalog guidance).
- `:app:assembleRelease` and `:app:assembleDebugAndroidTest`: passed. Instrumentation tests compiled but were not run on a device.
- APK signature verified; certificate SHA-256: `b96219f454a143583aa3cb43ff23b270cc798109f818b2aafe5d02f5aada7752`.
- Phone versionName `0.3.2`, versionCode `8`. Companion code unchanged; no companion APK release required.
- No device upgrade/migration, speech/UI, watch-relay, or live n8n/Google backend checks were run for this release.

## Unresolved backend requirements

- Supply/deploy the production n8n HTTPS URL, version/hosting, proxy timeout, authentication/user mapping, durable deduplication database and retention/backup policy.
- Configure Google OAuth ownership/consent/redirect URI, writable calendar IDs, timezone/duration/working-hours/conflict/stale-capture defaults, and invitation/recurrence/update/cancellation confirmation policies. Implement correlated clarification, verified provider receipts, and uncertain-write reconciliation; run the guide's acceptance checks against a test calendar. Model/provider and personal-data retention choices are still required.
- **Accepted-result blocker:** no authenticated asynchronous result endpoint or push channel has been supplied. Required: endpoint/channel, request-ID correlation, response/status schema, and retry/expiry semantics. `accepted` remains completion-unconfirmed; there is no polling/callback path or History retry for accepted requests. Do not replay accepted mutations to retrieve results.

See [the n8n guide](docs/N8N_ASSISTANT.md) for the exact contract and backend acceptance checks.
