# Alfred calendar foundation (0.3.3)

## Deployment

- Main workflow: `fH8eyNAffYOyMCmk` (Openwebui Custom Agent).
- Database setup and rollback-only ledger assertions: `kV39mnF8xxJ0mtjE`.
- Isolated Calendar create/update/delete check: `Hhx7LFpno4mH6XKm`.
- All workflows live at the personal project's root.
- Credential: **Alfred Postgres**, restricted `alfred` role, database/schema `alfred`.
- Schema source: [alfred-ledger.sql](alfred-ledger.sql).
- Model: user-selected **Deepseek V4.1 Flash**, without Gemini fallback.

Published version: `7c6b65b9-9a3e-42df-9bbe-93562fd4dacb` (0.3.5 Outline journals).
The **Calendar policy** node now targets `yashesh.s.1997@gmail.com`.
For future main-workflow write tests, set the draft policy to **Alfred Test**
before testing and restore the primary target before publishing. The separate
verification workflow is permanently restricted to Alfred Test.
Never accept calendar IDs from the request or model.
Do not use execution-mode strings as an isolation boundary:
the initial manual test showed that the Code-node mode did not equal `manual`.

## Implemented behavior

- Existing header authentication, explicit ping, validated capture envelope,
  non-streaming JSON replies, and Note input from `text`.
- Atomic PostgreSQL claim keyed by authenticated single-user mapping and request
  ID. JSONB equality rejects changed payloads under the same ID. Duplicate
  requests return persisted results instead of executing tools again.
- Model-only proposal extraction; calendar mutation tools removed from agents.
- Expiring opaque confirmation tokens tied to prior request and conversation.
  Corrections consume the old token, preventing approval of an earlier revision.
- Timed single-event create/update/delete, event listing and availability.
  Writes require an explicit `yes`, `confirm`, `confirmed`, `approve`, or
  `approved` reply to the current token. Optional final period/exclamation allowed.
- Capture-local date context, missing-duration clarification, stale-time checks,
  timezone-offset and daylight-saving ambiguity checks, conflict checks, and
  rejection of recurring or attendee-bearing update/delete targets.
- Deterministic provider create IDs, exact receipt-bound update/delete targets,
  stale receipt ETag rejection and conditional Google writes.
- Read-after-write verification before persisting `completed` and provider IDs,
  calendar identity and returned HTTPS links.
- Retry reconciliation reads the recorded provider ID without repeating writes.
  Non-calendar agent reports are stored as completion-unconfirmed `accepted`;
  Outline/Contacts completion is not independently verified.

## Verification evidence

Manual MCP executions exercised live PostgreSQL, model and Google connections.
These bypass inbound webhook authentication; they do not prove HTTP auth works.
An unauthenticated POST to the published production URL returned HTTP 403.
An authenticated real-HTTP ping still needs the credential holder's device check.

| Executions | Verified |
| --- | --- |
| 149–150, 152 | Restricted role, schema creation, first claim, duplicate owner, payload conflict and result replay assertions |
| 151 | Pinned ping returns exact capability envelope, without invoking tools |
| 154 | Dedicated test calendar create, ETag update, delete HTTP 204 |
| 159–160, 163–164 | Main workflow proposal, confirmation, verified create and delete in Alfred Test |
| 165, 167 | Saved result replay without provider calls; changed payload returns HTTP 409 response envelope |
| 166 | Live event listing, including empty results |
| 168–170 | Timezone validation, proposal correction, old-token rejection |
| 171–175 | Corrected create, receipt-linked ETag update and verified cleanup |
| 176–177 | Simulated lost deletion receipt recovered by reading cancelled provider event, without repeating mutation |

An early test (157) accidentally created a labeled event in the primary calendar
because of the mode-string assumption. Exact-event ETag-guarded cleanup returned
204 in execution 158. Fixed server-side test calendar selection replaced that
assumption. All temporary events created during these checks were deleted.

## Workout journals (0.3.5)

Say: “Log my workout: bench press, three sets of eight at 60 kg, 40 minutes.”
Use History **Correct** on the latest receipt to change a value. Reply to missing
detail questions with the requested units or quantities. Completed logs do not
require calendar-style approval because they record performed activity rather
than scheduling provider mutations.

Workout-specific extraction, storage, revisions and database functions were
removed at the user's request. Fitness captures use the existing Outline agent:
append to the performed day's journal or create it if absent. Corrections should
edit the relevant journal passage. Generic request deduplication remains; capture
text and responses can still appear in the shared request ledger and execution
history. Outline completion is reported as unconfirmed by the generic agent
response path, not independently verified as a structured database receipt.

## Shared operational limits

- No app polling/push endpoint: accepted results do not automatically refresh.
- No scheduled reconciliation worker. Same-ID retry reconciles recorded calendar
  operations; accepted entries need backend inspection because the current app
  does not offer Retry for them. Never submit a fresh copy to retrieve a result.
- Requests interrupted before dispatch remain pending for operator review; leases
  are not automatically stolen. Ambiguous provider reads remain unconfirmed.
- Single-user header credential maps to `alfred-primary`. Multi-user auth mapping
  requires separate server-side identities before sharing this endpoint.
- Updates/deletes currently require a prior Alfred receipt, not a fuzzy lookup of
  arbitrary existing events. Invitations, recurrence and all-day writes are outside
  this initial timed-event implementation.
- Listings return at most 250 events with explicit truncation text. Conflict checks
  fail closed if Google's 2,500-result page is incomplete.
- Model latency can exceed Convo's roughly 20-second budget; one observed proposal
  took 26 seconds. Task/Note offers the longer request budget.
- Database backups, retention, proxy timeout and phone/watch UI checks remain
  deployment/device responsibilities. No automatic ledger deletion is configured.
