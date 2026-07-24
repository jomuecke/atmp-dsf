# Issue D — Error-handling hardening

Parent: `docs/atmp-dsf-prd.md`
Label: ready-for-agent

## What to build

Make the loop robust to per-subject and per-cycle failures so a single bad subject or a transient
outage never stops the long-lived process.

- **Per-subject isolation**: an error sending one subject's bundle (e.g. the register rejects an unknown
  pseudonym/PID) is caught inside the multi-instance subprocess. The error is logged and appended as
  an audit entry to the start Task's `output`; the subject's state is **not** advanced (not marked
  seen, watermark not moved) so it is retried next cycle; processing continues with the next subject.
- **Whole-cycle failure** (register unreachable/unhealthy, API key rejected, FHIR store down): log, skip
  this tick, retry on the next interval. The instance never terminates.
- Errors recorded on the start Task `output` are auditable per cycle.

### Register-response handling (from `medic-import.yaml`)

The register's import API is stricter and more nuanced than a plain 2xx/non-2xx split. Handling is
derived from its OpenAPI spec:

- **A `2xx` is not success on its own.** The `201` acknowledgement (`MedicImportAck`) can still report
  per-entry problems while `success: true`: an unknown PID (`canBeSkippedAsError: true`), a
  non-skippable failure, or entries the register's schema rejected (`parseIssues`). The client parses
  the ack; any reported problem leaves the subject unhandled and audited so it is re-sent next cycle,
  instead of being counted as delivered. Per the product decision, an unknown PID is left **unseen**
  (not marked delivered) so it keeps being re-sent and stays visible in the audit until the pseudonym
  is provisioned — a `success` flag alone must not make the DIC believe everything worked.
- **Failures are classified by blast radius**: `401` (`E060`–`E063`, API key/auth), `5xx` and
  transport/timeout failures abort the whole cycle; `400`/`E064` and an unreadable ack fail only the
  current subject.
- **Outgoing-shape conformance**: the bundle carries `id` + `meta.lastUpdated`; each Observation keeps
  `meta.versionId`/`lastUpdated`/`source` (forwarded from the local store), a numeric-offset
  `effectiveDateTime` (bare `Z` rewritten), and a complete `valueQuantity`. An Observation that cannot
  satisfy the register schema is left out of the bundle and audited as a rejected Observation rather
  than vanishing silently; the rest of the subject is still sent.
- **Timeouts**: the register client has a connect and a request timeout so a hung register cannot block
  the cycle's job thread indefinitely; a timeout is handled as an unreachable register.

## Acceptance criteria

- [x] One subject's send failure does not abort the cycle; other subjects still send.
- [x] A failed subject is retried on the next cycle (state not advanced).
- [x] A whole-cycle failure skips the tick and the loop continues on the next interval.
- [x] Errors are appended to the start Task `output` with enough detail to audit (subject/pseudonym,
      cause, timestamp) — without leaking the API key or local patient identity.
- [x] Unit/behaviour test: factory/selection logic reports per-subject errors without advancing state;
      a bad subject in a batch leaves the others intact.
- [x] A `201` ack reporting an unknown PID / hard failure / `parseIssues` is not counted as delivered;
      the subject stays unseen and is audited (`SendToRegisterTest`).
- [x] `401` and `5xx`/timeout abort the cycle; `400`/`E064` fails only the current subject
      (`SendToRegisterTest`, `RegisterClient` classification).
- [x] Observations that cannot satisfy the register import schema are reported, not dropped silently
      (`ObservationBundleFactoryTest`).
- [ ] Demoable against Mockoon: seed one subject with pseudonym `ATMP-UNKNOWN` (mock returns a
      skippable failure) → it stays unseen and is retried while `ATMP-…` subjects with a valid PID
      succeed. Other scenario pseudonyms (`ATMP-HARDFAIL`, `ATMP-PARSEFAIL`, `ATMP-E064`, `ATMP-503`,
      `ATMP-SLOW`) exercise the remaining branches. See the mock's `README.md`.

## Blocked by

- Issue A — One-shot end-to-end send. (Best completed after B and C, since it hardens the loop and
  the incremental/first-sight state introduced there.)
