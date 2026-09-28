# Alfred 0.3.4 — structured workout logs

- Published workout intent extraction before existing calendar/assistant routing.
- PostgreSQL stores performed time/timezone, activity, optional duration, exercises,
  per-set repetitions and weight/unit, and notes. Missing units/quantities prompt
  a correlated clarification instead of silently inventing values.
- Workout receipts are returned only after the workout revision and request result
  commit together. History corrections update the same workout using optimistic
  revision checks; stale receipts cannot overwrite newer changes.
- Weight units are preserved as kg/lb; bodyweight and unspecified load are explicit.
  Capture time defaults are marked in notes. No calendar event is created for a log.
- Schema source: `docs/alfred-workouts.sql`. Runnable rollback-only database
  assertions remain in the **Alfred database setup** workflow.
- Phone version 0.3.4 / code 10; existing generic receipt and reply UI reused.
  Companion unchanged.

## Verification

- Live workout creation and 60 kg to 65 kg correction passed (executions 183–184).
- Missing-unit clarification and `kg` reply persisted two 10-rep, 50 kg sets
  (186–187). The initial incomplete-output failure was fixed before publication.
- Transactional create, duplicate replay, correction and stale-revision assertions
  passed with rollback (188). Integration workout fixtures were removed (189).
- Published workflow version `fe408247-738a-4737-955b-863111d64cc8`.
- `:app:testDebugUnitTest` passed with existing results up-to-date;
  `:app:assembleRelease` and release vital lint passed.
- APK signature verified with the existing release certificate:
  `b96219f454a143583aa3cb43ff23b270cc798109f818b2aafe5d02f5aada7752`.

## Limits

- No workout browse/export/delete UI, analytics, distance, heart-rate or calorie
  schema in this phase. Stored workout data lives in PostgreSQL; phone History
  contains the receipt and conversational response.
- Model extraction is validated for shape/ranges, not guaranteed transcription
  accuracy. Review captured text and correct the latest receipt when needed.
- One clarification reply took 49 seconds. Prefer Task/Note; Convo can time out.
- No new device/watch, concurrency stress, or database backup/restore checks.
- Workout routing adds a model call before non-workout requests.
