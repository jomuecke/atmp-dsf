# ATMP-DSF Process Plugin — Implementation Spec

Status: design agreed (grilling session 2026-06). Ready to build.

## 1. Purpose & Topology

A DSF process plugin that, **on each participating DIC**, periodically collects that
site's ATMP-study laboratory `Observation`s from the **local clinical FHIR store**,
pseudonymizes them, and pushes them to the **external MEDIC / integrate-ATMP REST API**.

- **Not** a DSF-to-DSF transfer. There is no DMS/HRP in the data path and no `receive`
  side. The only "remote" system is the MEDIC REST API, reached over HTTP — invisible
  to DSF authorization.
- Authored by **UKHD**, **distributed to all DICs participating in the ATMP study**.
- One running process instance per DIC; each DIC sends under its own BHZ identity
  (its `MEDIC-API-KEY`).

```
DIC clinical FHIR store            DIC BPE (this plugin)                MEDIC REST API
  ResearchStudy (ATMP)   --query-->  loop on timer:                       (external, DKFZ)
  ResearchSubject(+psn)              - find ATMP subjects
  Patient                            - per subject: collect labs,
  Observation (Lab)                    pseudonymize, build bundle  --POST-->  /api/medic-import
                                     - wait (interval), repeat
```

## 2. External API Contract (MEDIC / integrate-ATMP)

Confirmed by the platform owner (email) + the Postman collection.

- **Endpoint:** `POST {apiUrl}/api/medic-import` — body is a FHIR **`Bundle` of `type: collection`**
  whose entries are bare `Observation` resources (no `request.method`, not a transaction bundle).
  A single-resource variant `POST {apiUrl}/api/medic-import/observation` also exists.
- **Idempotency:** MEDIC stores each Observation **by entry `id`** and **updates** the record
  when the same `id` arrives again → **upsert by `Observation.id`**. The receiver never has to
  dedupe manually. *This is contingent on us sending a **stable, deterministic `Observation.id`**
  per source observation across runs.*
- **Subject matching:** `Observation.subject.reference` must be `Patient/<PID>` where `<PID>`
  already exists on the MEDIC side. **`<PID>` is the ATMP pseudonym.** A non-existent PID makes
  the request fail.
- **Auth:** header `MEDIC-API-KEY`, value = base64 of `{"bhz":"<bhz-id>","apiKey":"<key>"}`,
  **one per endpoint** (= the sending BHZ). Secret per site.
- **Staging base URL:** `https://staging.app.integrate-atmp.de`.

Open with platform owner (non-blocking): whether to keep the local `Observation.identifier`
(system/value) in the outgoing resource. **Decision for now: keep it** (their sample data has
it); finalize with the endpoint institution.

## 3. Local FHIR Domain Model (referenced MII profiles — not authored here)

| Resource | Profile | Role |
|---|---|---|
| `ResearchStudy` | `…/modul-studie/StructureDefinition/mii-pr-studie-studie` | the ATMP study; located by a configurable business identifier (default value `ATMP`) |
| `ResearchSubject` | `…/modul-studie/StructureDefinition/mii-pr-studie-proband` | `study`→ResearchStudy, `individual`→Patient, `identifier.value` = **ATMP pseudonym** (type `ANON`) |
| `Patient` | site-local | referenced by ResearchSubject and Observations |
| `Observation` | `…/core/modul-labor/StructureDefinition/ObservationLab` | `subject`→Patient, LOINC `code`, `status` |

Resolution path per subject: `ResearchSubject.individual` → `Patient` → `Observation?subject=Patient/<local-id>`.

## 4. Runtime Behaviour

### 4.1 Lifecycle
- **Started** by a Task message (`atmpDataTransferStart`) → a **single long-lived process
  instance** that **loops on a configurable BPMN cycle timer** (default `PT1H`).
- **Runs until stopped.** No natural end event.
- **Clean stop:** an **interrupting message event-subprocess** listening for
  `atmpDataTransferStop` terminates the instance immediately.
- **Singleton guard:** reject a second `…Start` while an instance is already running for the
  study (no double-collectors).
- **Restart semantics:**
  - BPE crash/redeploy → Camunda persists process variables (watermark + seen-set) → the timer
    resumes, no data loss, no re-bulk.
  - Explicit cancel + fresh start → re-bulk everything. **Harmless** because MEDIC upserts by `id`.

### 4.2 Per cycle (mirrors `mii-process-report` `report-autostart.bpmn`)
1. `SetTimer` — read `timer-interval` (Task input, else env/constant default) into the process
   variable the timer expression reads; read `force-bulk`/`first-execution`.
2. **Query ATMP `ResearchSubject`s** of the configured study (re-queried **every cycle** so
   newly-enrolled subjects are picked up automatically).
3. **Multi-instance subprocess, per subject:**
   - Determine mode: **bulk** if (`force-bulk` on this first cycle) OR subject **not yet seen**
     in this instance (`Set<pseudonym>`); else **incremental**.
   - Query `Observation` for the subject's Patient, filtered by **configured LOINC code list**
     and **`status=final`**; incremental adds `_lastUpdated=gt{watermark}`.
   - **Build a per-subject `Bundle` (`type: collection`)**: for each Observation — rewrite
     `subject.reference` → `Patient/<pseudonym>`, **preserve a stable `id`**, keep fields minimal
     (id, status, category, code, subject, effective[x], value[x]/component; local identifier kept
     for now — see §2).
   - `POST` the bundle to `/api/medic-import` with the `MEDIC-API-KEY` header.
   - On success: mark subject seen.
   - On error (e.g. unknown PID): log, append an audit entry to the **start-Task `output`**,
     **do not** mark seen / advance state (retried next cycle), continue to next subject.
4. After all subjects: advance the **global watermark** (see §4.3).
5. **Cycle timer** waits the interval, then loop to step 2.
- **Whole-cycle failure** (MEDIC unreachable / store down): log, skip this tick, retry next
  interval. Never terminate.

### 4.3 Watermark (incremental detection)
- **Single global watermark** = timestamp of the last completed cycle, stored as a process
  variable.
- To absorb BPE↔FHIR clock skew, set it to **cycle-start − small buffer (a few minutes)** and
  query with `gt`. Any harmless re-sends are absorbed by MEDIC's upsert.
- **Bulk-on-first-sight** closes the late-enrollment hole: a subject enrolled *after* its labs
  were loaded has Observations with old `_lastUpdated`; on first sight it gets a **full**
  (un-watermarked) query, then goes incremental. (Confirmed ETL pattern: labs pre-exist,
  `ResearchSubject` added on enrollment.)

## 5. Naming, Namespace, Authorization

- **Java package:** `de.ukhd.process.atmp` (UKHD authorship). Independent of which libraries are reused.
- **Module / `getName()`:** `atmp-dsf`.
- **Plugin's own FHIR/process URLs:** `http://ukhd.de/...` (own `ConstantsAtmp`). Reuse
  `mii-processes-common` **utilities** (FHIR client, logging) regardless of namespace; reuse
  `ConstantsBase` only for fixed DSF system URLs if convenient.
- **Process:** name `atmpDataTransfer`, URL `http://ukhd.de/bpe/Process/atmpDataTransfer`.
- **Messages / Task profiles:** `atmpDataTransferStart` / `task-atmp-data-transfer-start`,
  `atmpDataTransferStop` / `task-atmp-data-transfer-stop`.
- **Authorization** (in the `ActivityDefinition`, `extension-process-authorization`):
  - `requester`: `LOCAL_ROLE` (parent-org `medizininformatik-initiative.de`, role `DIC`)
    **+** `LOCAL_ROLE_PRACTITIONER` (same parent/role, practitioner-role `DSF_ADMIN`).
  - `recipient`: `LOCAL_ROLE` (same).
  - **No `REMOTE_ROLE`.** Same rules for the stop message.
  - Parent-org `medizininformatik-initiative.de` reused → zero per-site allow-list onboarding
    (participating DICs are already MII DICs). It is the single line to change if a dedicated
    ATMP consortium is ever mandated.
- **Study / pseudonym identifier systems:** site-specific, configurable (e.g. `https://ukhd.de/fhir/sid/…`).

## 6. Configuration

Per-endpoint, Spring `@Value("${…:default}")` + `@ProcessDocumentation`. Secrets as docker-secret files.

| Knob | Source | Notes |
|---|---|---|
| MEDIC base URL | env `…atmp.api.url` | default `https://staging.app.integrate-atmp.de` |
| `MEDIC-API-KEY` | **docker secret file** `…atmp.api.key.file` → `/run/secrets/atmp_api_key` | per-BHZ identity; never logged |
| LOINC code list | env `…atmp.observation.loinc.codes` | comma-separated (split like existing list props) |
| ATMP study identifier | env `…atmp.study.identifier.system` + `…value` | default value `ATMP` |
| Timer interval | **Task input** `timer-interval` (else env/constant default) | ISO-8601 duration, default `PT1H` |
| Force bulk | **Task input** `first-execution`/`force-bulk` (boolean/time) | one-shot resync, then incremental |

## 7. Artifacts to Author

### 7.1 FHIR (`src/main/resources/fhir/`, URLs under `http://ukhd.de/...`)
- `ActivityDefinition/atmp-data-transfer.xml` — process + authorization (§5).
- `StructureDefinition/task-atmp-data-transfer-start.xml` — inputs: `message-name`,
  `timer-interval` (opt), `first-execution`/`force-bulk` (opt).
- `StructureDefinition/task-atmp-data-transfer-stop.xml`.
- `CodeSystem/atmp-data-transfer.xml` + `ValueSet/atmp-data-transfer.xml` — codes
  `timer-interval`, `first-execution`, `error` (mirror NCT/report).
- `Task/task-atmp-data-transfer-start.xml`, `Task/task-atmp-data-transfer-stop.xml` — examples/seeds.
- No data profiles authored (`ResearchStudy`/`ResearchSubject`/`ObservationLab` are MII).

### 7.2 Java (`src/main/java/de/ukhd/process/atmp/`)
- `AtmpProcessPluginDefinition implements dev.dsf.bpe.v2.ProcessPluginDefinition`.
- `ConstantsAtmp`.
- `AtmpProcessPluginDeploymentStateListener` (optional, validate config/resources on deploy).
- `service/` (implement `dev.dsf.bpe.v2.activity.ServiceTask`):
  `SetTimer` (mirror report), `QueryResearchSubjects`, `BuildObservationBundle`
  (pseudonymize + minimize + watermark/first-sight), `SendToMedic`, `HandleError`.
- `client/`: `MedicClient` (spring-web `RestClient`, `MEDIC-API-KEY` header — fresh thin client,
  not NCT's fTTP/OAuth base). Local FHIR-store reads via reused `mii-processes-common` FHIR client.
- `variables/`: subject-reference value + serializer; watermark (`Instant`) and seen-set
  (`Set<String>`) carried as process variables.
- `spring/config/`: `AtmpConfig`, `DicFhirStoreClientConfig`, `MedicClientConfig`.
- `bpe/atmp-data-transfer.bpmn`: reworked draft — timer loop + multi-instance-per-subject +
  interrupting stop event-subprocess + per-task boundary-error → `HandleError`. (Drop the draft's
  "resolve TTP pseudonym".)
- `META-INF/services/dev.dsf.bpe.v2.ProcessPluginDefinition`.

### 7.3 `pom.xml` (mirror `mii-process-report`)
- dsf `2.1.0`, `dsf-bpe-process-api-v2`, `mii-processes-common 2.0.0.0`, `spring-web`.
- **Java 25**, `maven.compiler.release 25`, Maven ≥ 3.9.
- maven-shade (bundle `mii-processes-common`), formatter/impsort, `dsf-tools-documentation-generator`, JUnit.

### 7.4 Tests
- `ProcessPluginDefinitionTest`.
- FHIR profile validation tests (Task profiles, ActivityDefinition) via `dsf-fhir-validation`.
- Service unit tests: `BuildObservationBundle` (subject→pseudonym rewrite, stable id, LOINC+status
  filter, minimization), watermark + first-sight logic, `SendToMedic` (mock HTTP, header assertion).

## 8. Test-Setup Integration (`mii-processes-test-setup`)

- **Install on `dic1-bpe` only** (for now). Compose override mounts the plugin jar + the
  `atmp_api_key` secret + env (`atmp.api.url` → Mockoon, LOINC list, study identifier).
- **MEDIC mock:** add `docker/mockoon/medic-import-api.json` mocking `POST /api/medic-import`
  (+ `/observation`) → `200`, asserting the `MEDIC-API-KEY` header (mirror `nct-fttp-api.json`).
- **Clinical store:** `dic1-fhir-store` — neither HAPI nor Blaze auto-starts (manual start).
  Use **HAPI locally**; **Blaze in prod**. The FHIR-store client uses standard FHIR R4 search, so
  the store is config-only (no code change to switch).
- **Seed:** load `atmp-dsf/test/ressources/atmp_fhir_store_test_bundle` into `dic1-fhir-store`
  via a one-shot **HTTP POST** (README/script step).
- **Run:** POST `task-atmp-data-transfer-start` to `dic1`'s **DSF** FHIR endpoint → observe POSTs
  at the Mockoon mock across ticks; verify first-tick bulk vs. incremental; verify
  `…Stop` terminates the instance.

## 9. Build Order

1. Scaffold module (`pom.xml`, package, `ProcessPluginDefinition`, `META-INF/services`) — mirror `report`.
2. FHIR artifacts (ActivityDefinition, Task profiles, CodeSystem/ValueSet) + profile tests → green.
3. `MedicClient` + `SendToMedic` (+ mock test).
4. `QueryResearchSubjects` + `BuildObservationBundle` (pseudonymize, filter, minimize) + tests.
5. `SetTimer` + watermark/first-sight state + BPMN (timer loop, multi-instance, stop, error).
6. Test-setup: Mockoon mock, dic1 override + secret/env, seed bundle, end-to-end run.
7. Add `dic2` (second BHZ) once green.

## 10. Risks / Open Items
- Confirm MEDIC accepts the per-subject `collection` bundle shape; confirm per-entry vs
  whole-request error behaviour (per-subject bundles already isolate failures).
- Finalize whether to forward the local `Observation.identifier` (with endpoint institution).
- **Stable `Observation.id`** must equal the source id (or a deterministic derivation) — never
  regenerate, or MEDIC's upsert breaks.
- Confirm default timer interval `PT1H`.
- Java 25 toolchain must be available locally (inherited from `mii-processes-common 2.0.0.0`).
