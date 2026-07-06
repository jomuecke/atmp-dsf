# Issue C — Incremental watermark + bulk-on-first-sight + force-bulk

Parent: `docs/atmp-dsf-prd.md`
Label: ready-for-agent

## What to build

Make the loop send **only what changed** since the last cycle, while still backfilling history
correctly for participants enrolled after their labs were loaded.

- **Global watermark**: the timestamp of the last completed cycle, held as a process variable.
  Incremental cycles query `Observation?…&_lastUpdated=gt{watermark − small buffer}`. The buffer
  absorbs BPE↔FHIR-store clock skew; any harmless re-sends are absorbed by MEDIC's upsert-by-`id`.
- **Bulk-on-first-sight**: a `Set<pseudonym>` of subjects already handled in this instance. A subject
  not yet in the set gets a **full** (un-watermarked) query once, then joins the set and goes
  incremental. This covers the confirmed ETL pattern (labs pre-exist; `ResearchSubject` added on
  enrollment) and makes newly-enrolled subjects appear automatically (subject list re-queried each cycle).
- **Force-bulk**: a Task input (`first-execution`/`force-bulk`); when set, the first cycle ignores the
  watermark and re-sends everything, then resumes incremental.
- Watermark + seen-set persist across BPE restart with the rest of the process variables.

## Acceptance criteria

- [ ] Incremental cycle sends only Observations with `_lastUpdated` after the previous cycle (minus buffer).
- [ ] A subject seen for the first time in the instance is sent in full, then incremental thereafter.
- [ ] A newly-enrolled `ResearchSubject` (added between cycles) is picked up and backfilled in full.
- [ ] `force-bulk` Task input forces a one-shot full re-send, then incremental resumes.
- [ ] Watermark + seen-set survive a BPE restart (no unintended re-bulk).
- [ ] `ObservationBundleFactory`/selection unit tests cover: incremental vs full selection, first-sight
      full query, watermark advance, force-bulk override.
- [ ] Demoable against Mockoon: cycle 2 sends only new Observations; a new subject is backfilled;
      force-bulk re-sends all.

## Blocked by

- Issue B — Timer loop + clean stop.
