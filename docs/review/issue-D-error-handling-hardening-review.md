# Review: Issue D - Error-handling hardening

Reviewed against: `docs/issues/issue-D-error-handling-hardening.md`

## Verdict

Mostly implemented. Subject-level isolation, audit output, redaction, retry state, and loop survival are present. The remaining mismatch is whole-cycle failure handling for MEDIC outages and per-subject FHIR store failures.

## Findings

### Medium: MEDIC and per-subject FHIR outages are not handled as whole-cycle failures

Issue D explicitly says whole-cycle failures such as "MEDIC unreachable, FHIR store down" should log, skip this tick, and retry on the next interval.

Current state:

- `QueryResearchSubjects` handles failures while resolving the study/subject list as a cycle skip by returning an empty subject list and leaving the watermark unadvanced.
- `CreateSubjectBundle` catches Observation-query failures inside the per-subject subprocess and records them as subject failures.
- `SendToMedic` catches MEDIC send failures inside the per-subject subprocess and records them as subject failures.

Risk: a MEDIC outage produces one audit output per subject and attempts the rest of the batch instead of producing one cycle-level skip. Failed subjects are unmarked and retried, so this is mostly safe, but it does not satisfy the stated whole-cycle failure behavior.

Relevant files:

- `src/main/java/de/ukhd/process/atmp/service/QueryResearchSubjects.java`
- `src/main/java/de/ukhd/process/atmp/service/CreateSubjectBundle.java`
- `src/main/java/de/ukhd/process/atmp/service/SendToMedic.java`
- `src/main/resources/bpe/atmp-data-transfer.bpmn`

## Test Gaps

- The "bad subject in a batch leaves others intact" behavior is covered at `SeenSubjects` state-helper level, not by driving the actual delegate/BPMN path.
- This leaves the wiring around `atmpSubjectError`, `atmpSubjectBundle`, and `Task.output` persistence untested.

## Acceptance Checklist

- [x] One subject's send failure does not abort the cycle; other subjects still send.
- [x] A failed subject is retried on the next cycle via `SeenSubjects.unmark`.
- [ ] A whole-cycle failure fully skips the tick for MEDIC and FHIR-store outages.
- [x] Errors are appended to the start Task output with pseudonym, cause, and timestamp.
- [x] Audit output redacts local patient identity and does not expose the API key.
- [ ] Delegate/BPMN-level behavior test for a bad subject in a batch.
- [ ] Demo against Mockoon was not verified in this review.

## Verification

Command run:

```sh
mvn test
```

Result:

- 36 tests passed
- 0 failures
- 0 errors

