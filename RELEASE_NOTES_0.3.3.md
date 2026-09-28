# Alfred 0.3.3 — n8n calendar foundation

- Published the existing n8n endpoint with envelope validation, authenticated ping
  routing, corrected Note input and non-streaming JSON results.
- Added a PostgreSQL request ledger, duplicate result replay, changed-payload
  rejection, expiring clarification tokens and stale-approval invalidation.
- Calendar writes require confirmation; create/update/delete use deterministic
  create IDs, receipt-bound targets, conflict checks and ETag preconditions.
  Results are verified against Google before completion receipts are saved.
- Added calendar listing and availability and read-only reconciliation on retries
  of uncertain calendar operations. Preserved the user's Deepseek V4.1 Flash model.
- Phone versionName 0.3.3 / versionCode 9. No new client behavior; companion unchanged.

## Completed verification

- Live database ownership, schema setup and rollback-only ledger assertions.
- Live test-calendar create/update/delete and main-workflow confirmation, correction,
  saved-result replay, HTTP 409 response envelope, stale-token rejection and listing.
- Simulated lost deletion receipt recovered without repeating the mutation.
- Unauthenticated production HTTP request rejected with 403; pinned ping passed.
- `:app:testDebugUnitTest` passed (existing results up-to-date).
- `:app:assembleRelease` passed, including release vital lint.
- APK signature verified; SHA-256 certificate:
  `b96219f454a143583aa3cb43ff23b270cc798109f818b2aafe5d02f5aada7752`.

## Limits and outstanding checks

- No authenticated real-HTTP/device/watch test or full lint run in this phase.
- No automated concurrency stress test, provider outage injection or backup/restore test.
- No app asynchronous result channel or scheduled reconciliation worker.
- Non-calendar agent reports remain completion-unconfirmed; Outline/Contacts were
  not live-tested. Arbitrary existing-event lookup, invitations, recurring and
  all-day writes are not part of this initial timed-event subset.
- Model latency can exceed Convo's 20-second budget; Task/Note supports longer calls.
- One early test event accidentally reached the primary calendar due to an execution
  mode assumption. It was removed using its verified ETag; fixed calendar selection
  replaced that assumption. All temporary test events were deleted.

See [deployment details and execution evidence](docs/N8N_DEPLOYMENT.md).
