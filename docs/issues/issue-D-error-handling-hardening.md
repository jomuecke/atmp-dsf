# Issue D — Error-handling hardening

Parent: `docs/atmp-dsf-prd.md`
Label: ready-for-agent

## What to build

Make the loop robust to per-subject and per-cycle failures so a single bad subject or a transient
outage never stops the long-lived process.

- **Per-subject isolation**: an error sending one subject's bundle (e.g. MEDIC rejects an unknown
  pseudonym/PID) is caught inside the multi-instance subprocess. The error is logged and appended as
  an audit entry to the start Task's `output`; the subject's state is **not** advanced (not marked
  seen, watermark not moved) so it is retried next cycle; processing continues with the next subject.
- **Whole-cycle failure** (MEDIC unreachable, FHIR store down): log, skip this tick, retry on the next
  interval. The instance never terminates.
- Errors recorded on the start Task `output` are auditable per cycle.

## Acceptance criteria

- [ ] One subject's send failure does not abort the cycle; other subjects still send.
- [ ] A failed subject is retried on the next cycle (state not advanced).
- [ ] A whole-cycle failure skips the tick and the loop continues on the next interval.
- [ ] Errors are appended to the start Task `output` with enough detail to audit (subject/pseudonym,
      cause, timestamp) — without leaking the API key or local patient identity.
- [ ] Unit/behaviour test: factory/selection logic reports per-subject errors without advancing state;
      a bad subject in a batch leaves the others intact.
- [ ] Demoable against Mockoon: seed one subject with a pseudonym the mock rejects → it fails alone,
      others succeed, and it is retried next cycle.

## Blocked by

- Issue A — One-shot end-to-end send. (Best completed after B and C, since it hardens the loop and
  the incremental/first-sight state introduced there.)
