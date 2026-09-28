# Alfred 0.3.5 — workouts in Outline journals

- Removed workout-specific extraction, validation, persistence and correction nodes.
- Published instructions directing fitness captures to the performed day's Outline
  journal: append when present, create otherwise, preserve unrelated journal text.
- Dropped workout and workout revision tables and the save_workout function.
  Removed structured workout metadata and pending workout clarification records
  from the generic ledger. Removed setup steps that could recreate the schema.
- Generic request capture text, responses and deduplication remain shared across
  all intents; this is not a purge of workout text from historical request logs.
- Database cleanup execution 191 passed and confirmed all workout-specific
  tables/functions absent. Workflow update validation returned no warnings.
- No live Outline document was modified to test these instructions. Outline
  completion remains unconfirmed by the existing generic response path.
- Phone version 0.3.5 / code 11. Companion unchanged.
- `:app:assembleRelease`, release vital lint and `git diff --check` passed.
  No new device tests or unit-test rerun for this backend removal/version bump.
