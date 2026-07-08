# Final review — atmp-dsf, issues B–E (diff `03f22e0...HEAD`)

> **Resolution status (2026-07-08):** All findings addressed.
> - **1–3**: stop reworked to message start event + signal broadcast (report-autostart pattern), duplicate guard reworked to a DSF-FHIR-Task query with oldest-wins ordering, `operaton-engine` dependency removed.
> - **4 + 10**: `force-bulk` and `first-execution` removed entirely (CodeSystem, profile slice, Task template, constants, services, tests). Rationale: a fresh instance always starts with an empty seen-set and no watermark, so its first cycle is inherently a full send — a force-bulk start input can never change behaviour; "force full re-send" = stop + start. **Deliberate deviation from issue C's acceptance criterion**, documented in `SetTimer`/`ObservationBundleFactory` javadoc.
> - **5**: `AuditLog` caps error outputs at 20, oldest dropped first, non-error outputs untouched.
> - **6**: `MedicClient` throws `MedicUnreachableException` on IO failure; `SendToMedic` audits it once as a cycle error, sets `atmpCycleAborted`, remaining subjects are skipped quietly and unmarked; watermark promotion is withheld after an aborted cycle.
> - **7**: stale `src/resources/` tree and `.DS_Store` files removed from git.
> - **8**: profile regex now mirrors `Duration.parse` (no bare `P`/`PT`, no calendar units); `SetTimer` additionally falls back to the default interval (with audit entry) instead of incidenting.
> - **9**: documented in `advanceCycleState` javadoc (deliberate one-interval overlap, absorbed by upsert).
> - **11**: `SendToMedicTest` (5 cases) and `CreateSubjectBundleTest` (2 cases) cover the Issue D error wiring including redaction, cycle abort and skip behaviour.

Two-axis review before test-setup deployment. Fixed point: `03f22e0` (issue A commit).
Commits reviewed: `8c13f88` (C), `2725174` (D), `8948d39` (E), `0b00857`, `e34ecde`, `206445e` (B).

Both axes were reviewed by independent agents; **both independently flagged findings 1 and 2 below as blockers.**

---

## Blockers (fix before test setup)

### 1. CRITICAL — plugin will not deploy: `RuntimeService` bean does not exist in the v2 plugin context

`AtmpConfig.rejectDuplicateStart(RuntimeService)` (`spring/config/AtmpConfig.java:91`) injects Operaton's `RuntimeService`. A DSF v2 plugin's Spring context has as its only parent the API context (`ApiServicesSpringConfiguration` in dsf-bpe-process-api-v2-impl), which exposes `TaskHelper`, `FhirClientProvider`, etc. — **no engine beans**. Context refresh throws `UnsatisfiedDependencyException` / `NoSuchBeanDefinitionException`, and the **entire plugin (all of B–E) fails to load**, not just the guard.

The `operaton-engine` `provided`-scope dependency (`pom.xml:49-54`) only fixes compilation; even if a bean existed, the plugin classloader's engine classes are a different class space than the BPE's. `RejectDuplicateStartTest` passes `null` for the service, so tests don't catch it.

**Fix direction:** drop the engine-query approach. Check for another in-progress start Task via the FHIR client, or redesign per finding 2 (message start event pattern makes the guard queryable via FHIR Tasks).

### 2. HIGH — stop message cannot be correlated; the shipped stop Task can never stop the loop

Issue B: "`atmpDataTransferStop` … terminates the running instance immediately." The stop is wired as an event-subprocess message. DSF's `TaskHandler` generates a **random** business key when a Task carries none, correlates by business key, finds no instance, falls through to `correlateStartMessage()`, and fails ("Unable to correlate Task"). The stop profile has `business-key` min=0 and the example `Task/task-atmp-data-transfer-stop.xml` has none — so the shipped stop path is dead unless an operator manually copies the start instance's business key.

This was the prior B-review's explicit open question ("Does DSF correlate … without a business key?") — left unresolved. `report-autostart.bpmn` (mii-process-report) solves the same problem with a **stop message start event + signal broadcast**; the issue said to mirror that pattern.

### 3. HIGH — duplicate-start guard logic is race/visibility-broken even if wired (Issue B)

`RejectDuplicateStart.java:44-47`: `count() > 1` assumes the current instance is visible in the query.
- If the service task runs in the instance-creating transaction (no async continuation), the new instance is uncommitted → a true duplicate sees count=1 → guard never fires.
- If visible, two near-simultaneous starts both see 2 → **both** terminate → zero loops running.
- `.active()` ignores suspended instances.
- The rejected duplicate ends via a normal end event: its start Task completes successfully with no error output — a weak rejection signal for the operator.

### 4. HIGH — `force-bulk` input is a permanent no-op (Issue C)

`ObservationBundleFactory.queryLowerBound` (`:70`) returns the full query whenever `watermark == null`. The watermark first becomes non-null in cycle 2, but `QueryResearchSubjects.advanceCycleState` (`:97-106`) resets `atmpForceBulk=false` at the **start** of cycle 2 — before any subject query consults it. So forceBulk is only ever read while the watermark is null (cycle 1, which is full anyway). The documented behavior ("force full re-send") is unreachable; the CodeSystem code, profile slice, and tests exercise dead config.

**Fix direction:** consume forceBulk in the same cycle it's read (e.g. pass "bulk this cycle" as a per-cycle flag into the query, reset it *after* the cycle's queries), or treat it as "clear watermark + seen state".

---

## Should fix

### 5. MEDIUM — unbounded growth of the start Task via audit outputs (Issue D)

`AuditLog.appendError` (`AuditLog.java:85-100`) appends an `error` output and `updateTask` on every failure. A persistent failure (FHIR store down, misconfigured LOINC) on an hourly timer adds ~24 outputs/day forever to one long-lived Task — ever-growing FHIR resource, ever-slower updates. `QueryResearchSubjects` also audits every skipped cycle (`:75`). Consider a cap, dedup of repeated identical errors, or logging-only after N repeats.

### 6. MEDIUM — whole-cycle MEDIC outage handled per-subject, not as a cycle skip (Issue D)

Issue D: "Whole-cycle failure (MEDIC unreachable…): log, skip this tick." Only `QueryResearchSubjects` implements a cycle skip; a MEDIC outage in `SendToMedic` produces one audit output **per subject** and the watermark still advances next cycle. Data-safe only because every failed subject is unmarked → full per-subject re-query; but noisy and not what the issue specified.

### 7. MEDIUM — stale legacy BPMN under `src/resources/` should be deleted, not groomed

`src/resources/bpe/atmp-data-transfer.bpmn` violates the documented layout (BPMN belongs in `src/main/resources/bpe`). It's an NCT-derived draft; commit `0b00857` blanked `camunda:class=""` attributes instead of deleting the file — it's dead, unpackaged, and now invalid for any engine. Delete it. Also remove the tracked/modified `.DS_Store` files (`src/.DS_Store`, `src/resources/.DS_Store`) and gitignore them.

---

## Minor

8. **LOW — validation/parse mismatch for `timer-interval`**: the profile regex accepts `"P"` and `"PT"`, which `Duration.parse` (`SetTimer.java:66-69`) rejects → a Task that validates still incidents the process before the loop starts, with no audit entry.
9. **LOW — watermark re-sends one full interval every cycle**: watermark = *previous* cycle's start (`QueryResearchSubjects.java:100`), so each incremental query re-fetches the whole prior interval plus buffer. Harmless (upsert), but doubles steady-state traffic; also deviates from spec §4.2 step 4 ("**After all subjects**: advance the global watermark") and from issue D ("watermark not moved" on failure). Deliberate deviation — document it or move the advance to end-of-cycle.
10. **LOW — spec §7.1 `first-execution` input**: the code exists in the CodeSystem but has no profile slice and is never read; only `force-bulk` (new code, mild naming divergence from spec) is wired. Remove the dead code or wire it.
11. **LOW — untested error wiring (Issue D acceptance)**: "factory/selection logic reports per-subject errors without advancing state" — only `SeenSubjects`/`AuditLog` helpers are tested; `CreateSubjectBundle`/`SendToMedic` error paths (`atmpSubjectError`, Task-output persistence) have no test. Prior D review flagged this; still open.
12. **Judgement — direct `operaton-engine` dependency** (`pom.xml:49`) bypasses the v1/v2 plugin-API abstraction the workspace conventions document; it's also the root cause of finding 1. Removing the engine approach removes the dependency.
13. **Judgement — new `audit/` package** isn't in the documented layout (`message/`, `service/`, `variables/`, `spring/config/`); reasonable addition, just noting.
14. **Scope creep (all defensible):** `README.md` deletion (GitLab boilerplate); new env knob `de.ukhd.atmp.watermark.buffer` (spec says "small buffer", knob unrequested).

---

## What's in good shape

- **Issue C** otherwise faithful: `queryLowerBound`, `gt{watermark−buffer}`, first-sight full query per subject, persisted cycle state.
- **Issue E** complete: dic2 pom copy/clean, dic2 seed bundle, dic2 env/secret/mockoon in `mii-processes-test-setup/docker/docker-compose.override.yml`.
- **`SeenSubjects`** is safe: sequential multi-instance, copy-on-write lists; `markSeen`/`unmarkSeen` ordering in `SendToMedic` is correct.
- FHIR namespaces (`http://ukhd.de/...`) satisfy the workspace convention; JUnit 4 matches existing tests.

---

## Verdict

**Not ready for the test setup as-is.** Finding 1 means the plugin most likely won't even load, and finding 2 means the stop path is dead. Recommended order: fix 1+2 together by mirroring `report-autostart.bpmn`'s stop-message-start-event + signal pattern (which also gives a FHIR-Task-based duplicate guard, resolving 3), then fix 4 (force-bulk), then 5–7. Findings 8–14 can ride along or wait.
