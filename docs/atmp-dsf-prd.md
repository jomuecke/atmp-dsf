# PRD — ATMP-DSF Process Plugin

Source design: `docs/atmp-dsf-implementation-spec.md` (grilling session, 2026-06).
Status: Step 1 (module scaffold) implemented; this PRD covers the full build.

## Problem Statement

Sites participating in the ATMP study (BHZ centres) hold their patients' laboratory results in a
local FHIR store, but the ATMP / integrate-ATMP ("MEDIC") platform at DKFZ needs those results
delivered continuously and pseudonymized. Today there is no automated, governed way for a site to
keep MEDIC up to date: a site would have to export, pseudonymize and upload lab values by hand,
repeatedly, without a reliable record of what has already been sent. UKHD needs to build this once
and hand the same artefact to every participating site, where each site can configure it for their
own credentials without touching code.

## Solution

A DSF process plugin (`atmp-dsf`), authored by UKHD and distributed to every participating DIC, that
runs on the site's own DSF BPE. An operator starts it once; it then runs on a configurable timer
(default hourly). Each cycle it finds the site's ATMP study participants, collects their relevant
laboratory Observations, rewrites each Observation onto the patient's ATMP pseudonym, and POSTs them
as a FHIR collection bundle to the MEDIC REST API using the site's API key. The first time it sees a
participant it sends their full history; thereafter it sends only what changed since the last cycle.
It can be stopped cleanly with a stop message. Because MEDIC upserts by `Observation.id`, re-sends are
harmless, so the site never has to reconcile duplicates.

The data destination (MEDIC) is an external REST API reached over HTTP — it is **not** a DSF endpoint.
There is no DSF-to-DSF messaging, no DMS, and no receive side.

## User Stories

1. As a participating site operator, I want to start the ATMP transfer once via a DSF Task, so that data begins flowing to MEDIC without manual exports.
2. As a participating site operator, I want the process to run automatically on a timer, so that MEDIC stays current without me triggering each run.
3. As a participating site operator, I want to configure the timer interval, so that I can match my site's freshness needs and load constraints.
4. As a participating site operator, I want the first run to send each participant's full lab history, so that MEDIC is fully backfilled when we onboard.
5. As a participating site operator, I want subsequent runs to send only new/changed Observations, so that I don't waste bandwidth re-sending everything every hour.
6. As a participating site operator, I want a one-shot "force bulk" option when starting, so that I can deliberately force a full resync when needed.
7. As a participating site operator, I want to stop the running process cleanly via a Task, so that I can halt transfers immediately without killing the engine.
8. As a participating site operator, I want a second start to be rejected while one is already running, so that I don't accidentally run two collectors that double-send.
9. As a participating site operator, I want the process to resume after a BPE restart/redeploy without re-sending everything, so that planned maintenance doesn't cause a full re-bulk.
10. As a participating site operator, I want newly enrolled study participants to be picked up automatically, so that I don't have to restart the process when our ETL adds a `ResearchSubject`.
11. As a participating site operator, I want a newly enrolled participant's pre-existing labs to be sent in full on first sight, so that late enrollment doesn't silently skip their history.
12. As a participating site operator, I want only the configured LOINC codes with `status=final` to be sent, so that only the agreed lab values leave the site.
13. As a participating site operator, I want the MEDIC base URL, API key, LOINC list and study identifier to be configuration, so that I can deploy the same plugin at my site without code changes.
14. As a participating site operator, I want the MEDIC API key supplied as a secret file, so that it never appears in logs, environment dumps or the Task.
15. As a data protection officer, I want each outgoing Observation's subject reference rewritten to the ATMP pseudonym, so that no local patient identity leaves the site.
16. As a data protection officer, I want outgoing Observations minimized to the agreed fields, so that we don't leak local re-identifying data.
17. As the MEDIC platform, I want each Observation to carry a stable `id`, so that repeated sends update the existing record instead of creating duplicates.
18. As a participating site operator, I want a failure for one participant (e.g. unknown pseudonym at MEDIC) to be isolated, so that other participants' data still transfers.
19. As a participating site operator, I want a participant that failed to be retried on the next cycle, so that transient errors self-heal without intervention.
20. As a participating site operator, I want a whole-cycle failure (MEDIC unreachable, store down) to skip that tick and retry next interval, so that the process survives outages without terminating.
21. As a participating site operator, I want errors recorded on the start Task output, so that I can audit what failed and when.
22. As a DSF administrator, I want only my local DIC organization (or a DSF_ADMIN practitioner) to be able to start/stop the process, so that no remote party can trigger it.
23. As a DSF administrator, I want the plugin to install on any MII DIC without per-site authorization edits, so that distribution is friction-free.
24. As a developer, I want the transform/selection logic in a pure, unit-testable class, so that I can verify pseudonymization, filtering and watermarking without booting the engine.
25. As a developer, I want the authored FHIR resources validated against DSF validation, so that profile errors are caught at build time.
26. As a developer, I want to run the whole thing against a local Docker test-setup with a mocked MEDIC API, so that I can verify end-to-end behaviour before deploying to a site.
27. As a developer, I want to switch the local FHIR store between HAPI and Blaze by configuration, so that I can develop on HAPI and run Blaze in production.

## Implementation Decisions

**Plugin identity & packaging**
- New DSF process plugin, module `atmp-dsf`, groupId `de.ukhd`, package `de.ukhd.process.atmp`, version `1.0.0.0`.
- **Process API v2** (`dev.dsf.bpe.v2`), `mii-processes-common 2.0.0.0`, dsf `2.1.0`, **Java 25 / Maven ≥ 3.9**. pom mirrors `mii-process-report`; `mii-processes-common` shaded in; `copy-to-test-setup` profile targets **dic1 only**.
- Reuse `mii-processes-common` utilities (FHIR client, logging); the plugin owns all its identifiers under the `http://ukhd.de/...` namespace via `ConstantsAtmp` (name base `ukhdde_`, matching the URL host).

**Process model (single process `atmpDataTransfer`)**
- Structurally mirrors `mii-process-report`'s `report-autostart.bpmn`: started by message `atmpDataTransferStart`; a `SetTimer` service seeds a process variable read by a BPMN intermediate **cycle timer** (`<timeDuration>` = ISO-8601 duration, **default `PT1H`**); the loop has **no natural end**.
- Clean stop via an **interrupting message event-subprocess** listening for `atmpDataTransferStop`.
- **Singleton guard**: reject a second start while an instance is running for the study.
- Each cycle: re-query the ATMP `ResearchSubject`s → **multi-instance subprocess per subject** → select Observations → build per-subject bundle → POST to MEDIC → advance state → wait (timer) → repeat.

**Selection & watermarking**
- **Global watermark** = timestamp of the last completed cycle, held as a process variable; queries use `_lastUpdated=gt{watermark − small buffer}` (buffer absorbs BPE↔FHIR clock skew; harmless overlap absorbed by MEDIC upsert).
- **Bulk-on-first-sight**: a `Set<pseudonym>` of subjects handled this instance; an unseen subject gets a full (un-watermarked) query, then goes incremental. Closes the late-enrollment gap (labs pre-exist, `ResearchSubject` added on enrollment).
- **Force-bulk** Task input: first cycle ignores the watermark, then resumes incremental.
- Selection filter: configured **LOINC code list** + **`status=final`**.

**Outgoing transform (the domain core)**
- Per subject, build a FHIR **`Bundle` of `type collection`** (per-subject for error isolation).
- Per Observation: rewrite `subject.reference` → `Patient/<pseudonym>` (pseudonym from `ResearchSubject.identifier.value`); **preserve the source `Observation.id` unchanged** (idempotent upsert); keep the local `Observation.identifier`; minimize to the agreed fields (id, status, category, code, subject, effective[x], value[x]/component).
- POST to `{apiUrl}/api/medic-import` with header `MEDIC-API-KEY`.

**External MEDIC contract**
- Upsert keyed by `Observation.id`; `subject.reference` PID must already exist at MEDIC (the pseudonym) or the request errors. `MEDIC-API-KEY` is per-site (the BHZ identity), base64 of `{"bhz":…,"apiKey":…}`.

**Error handling**
- Per-subject isolation: on error, log + append an audit entry to the start-Task `output`, **do not** mark the subject seen / advance its state, continue. Whole-cycle failure: skip the tick, retry next interval. Never terminate the loop.

**Restart semantics**
- Camunda persists process variables (watermark + seen-set) → BPE crash/redeploy resumes with no re-bulk. Explicit cancel + fresh start re-bulks (harmless due to upsert).

**Authorization & config**
- `ActivityDefinition` authorizes `requester` = `LOCAL_ROLE` (DIC) + `LOCAL_ROLE_PRACTITIONER` (DSF_ADMIN), `recipient` = `LOCAL_ROLE` (DIC), under parent-org `medizininformatik-initiative.de`, **no `REMOTE_ROLE`**; same for the stop message. (Single line to change if a dedicated ATMP consortium is ever required.)
- Per-site config: env `…atmp.api.url` (default staging), `…atmp.observation.loinc.codes` (comma list), `…atmp.study.identifier.system`/`…value` (default `ATMP`); **docker-secret file** `…atmp.api.key.file`. Task inputs: `timer-interval` (opt), `first-execution`/`force-bulk` (opt).

**FHIR artifacts authored** (under `http://ukhd.de/...`): `ActivityDefinition/atmp-data-transfer`; `StructureDefinition` task profiles `task-atmp-data-transfer-start` / `-stop`; `CodeSystem` + `ValueSet` `atmp-data-transfer` (codes `timer-interval`, `first-execution`, `error`); example `Task`s. Data profiles (`ResearchStudy`/`ResearchSubject`/`ObservationLab`) are referenced MII profiles, not authored.

**Module decomposition (seam)**
- Thin engine-wired v2 `ServiceTask`s (orchestration only): `SetTimer`, `QueryResearchSubjects`, `SendToMedic`, `HandleError`.
- **Pure unit `ObservationBundleFactory`** — `createFrom(observations, pseudonym, …)` plus the watermark/first-sight selection decision — holds all domain logic, no engine dependency. (Directly analogous to NCT's `TransactionBundleFactory`.)
- Clients: `MedicClient` (HTTP POST + `MEDIC-API-KEY` header); local FHIR-store reads via reused `mii-processes-common` FHIR client. Store is HAPI or Blaze by configuration (standard FHIR R4 search; no code change to switch).

## Testing Decisions

Good tests assert **external behaviour, not implementation details** — feed known inputs, assert outputs — so the thin engine-wired adapters are *not* unit-tested; the pure logic and the authored resources are. Three seams, mirroring the v2 `mii-process-report` and v1 `nct-process-data-transfer` prior art:

1. **`ObservationBundleFactory` unit tests** (primary behavioural seam). Prior art: NCT `fhir/mdat/TransactionBundleFactoryTest` (feeds sample FHIR bundles, asserts the produced bundle) and report `bpe/SearchBundleCheckServiceTest`. Cover: correct selection (LOINC + `status=final` + watermark; full query on first-sight); correct outgoing bundle (subject→pseudonym rewrite, **stable id preserved**, fields minimized, local identifier kept, `type collection`, per-subject grouping); per-subject error isolation (a bad subject does not advance state or block others).
2. **FHIR profile validation** — `fhir/profile/TaskProfileTest` (start + stop profiles), `fhir/profile/ActivityDefinitionProfileTest`, against `dsf-fhir-validation` `ValidationSupport`. Prior art: report/NCT `fhir/profile/*`.
3. **`AtmpProcessPluginDefinitionTest`** — plugin loads; declared BPMN models + FHIR resources resolve. Prior art: report/NCT `bpe/*ProcessPluginDefinitionTest`.

End-to-end (dic1 BPE + Mockoon MEDIC mock + seeded store) is **manual verification** in v1, not an automated test.

## Out of Scope

- Any DSF-to-DSF messaging, DMS/HRP involvement, encryption, or a receive-side plugin.
- TTP/pseudonymization *resolution* — the pseudonym is already on `ResearchSubject.identifier`.
- Authoring the data profiles (`ResearchStudy`/`ResearchSubject`/`ObservationLab` are referenced MII profiles).
- Registering pseudonym-patients at MEDIC (done out-of-band; a missing PID is an error case, not our responsibility to create).
- Externalized/durable watermark persistence beyond Camunda process variables (deliberate cancel ⇒ re-bulk is accepted, harmless under upsert).
- A dedicated ATMP consortium/parent-organization (reuse MII).
- Automated end-to-end CI; multi-site rollout beyond dic1 (dic2 added once green).
- A FHIR `ValueSet`-governed LOINC list (env comma-list for v1).

## Further Notes

- **Build order** (spec §9): 1 scaffold *(done)* → 2 FHIR artifacts + profile tests → 3 `MedicClient` + `SendToMedic` → 4 `QueryResearchSubjects` + `ObservationBundleFactory` + unit tests → 5 `SetTimer` + watermark/first-sight state + BPMN (timer loop, multi-instance, stop, error) → 6 test-setup (Mockoon mock, dic1 override + secret/env, seed bundle, e2e) → 7 add dic2.
- **Open items to confirm with the MEDIC/endpoint institution**: that MEDIC accepts the per-subject `collection` bundle shape and its per-entry vs whole-request error behaviour; finalize whether the local `Observation.identifier` is forwarded (kept for now). Confirmed: source `Observation.id`s are stable (upsert works); default interval `PT1H`.
- **Toolchain**: requires Java 25 and the `github-mii` token (`read:packages`) to resolve `dsf-*` and `mii-processes-common`.
- **References in-repo**: timer/start/stop + v2 conventions → `mii-process-report`; REST client + pseudonymize-and-send + the pure-factory test pattern → `nct-process-data-transfer`.
