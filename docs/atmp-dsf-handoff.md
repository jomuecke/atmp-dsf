# ATMP-DSF — Session Handoff

Resume point for a fresh session. Read this first, then the spec + PRD + issue files.

## TL;DR — where we are

- **Goal:** build `atmp-dsf`, a DSF v2 process plugin that runs on each participating DIC, periodically
  collects ATMP-study lab `Observation`s from the local FHIR store, pseudonymizes them, and POSTs them to
  the external **ATMP register (integrate-ATMP) REST API**. Not a DSF-to-DSF transfer.
- **Matt flow position:** `grill ✅ → to-prd ✅ → to-issues ✅`. **Step 1 (module scaffold) implemented.**
  Next action: **`/implement` Issue A** in a fresh session.
- **Tracker:** none wired (repo origin = UKHD GitLab `git.med.uni-heidelberg.de:imi/public/medic/atmp-dsf`;
  `gh` is github.com). Decided **not** to run `/setup-matt-pocock-skills` — local/markdown workflow is enough.

## Key artifacts (all under `atmp-dsf/`)

- `docs/atmp-fhir-server-data-requirements.md` — pre-existing: how sites represent ATMP data (ResearchStudy/
  ResearchSubject/Patient/ObservationLab; pseudonym on `ResearchSubject.identifier`).
- `docs/atmp-dsf-implementation-spec.md` — full design (the grilling output). Source of truth.
- `docs/atmp-dsf-prd.md` — PRD (27 user stories, decisions, 3 test seams, scope).
- `docs/issues/issue-A..E-*.md` — 5 vertical-slice issues (see below).
- **Scaffold already written:** `pom.xml` (v2, Java 25, mirrors `mii-process-report`, shade common,
  `copy-to-test-setup`→dic1 only), `eclipse-formatter-config.xml`, `src/main/java/de/ukhd/process/atmp/`
  (`AtmpProcessPluginDefinition`, `ConstantsAtmp`, `spring/config/AtmpConfig`),
  `src/main/resources/META-INF/services/dev.dsf.bpe.v2.ProcessPluginDefinition`,
  `src/main/resources/bpe/atmp-data-transfer.bpmn` (still the throwaway draft — reworked in Issue B).
- Stray `src/resources/bpe/atmp-data-transfer.bpmn` (wrong path, original draft) — can be deleted.
- **Not yet built/run:** no FHIR artifacts declared (`getFhirResourcesByProcessId()` returns empty map);
  `mvn verify` not run (needs Java 25 + `github-mii` token + dsf 2.1.0 / common 2.0.0.0).

## Issues (vertical slices)

- **A — one-shot end-to-end send (tracer bullet)** [no blocker]: minimal FHIR artifacts + `ObservationBundleFactory`
  (pseudonymize, LOINC+`status=final` filter, minimize, stable id, send-all) + `RegisterClient` + minimal BPMN
  (start→query→multi-instance→build→POST→end) + 3 test seams + test-setup (Mockoon mock, dic1, seed bundle).
- **B — timer loop + clean stop** [A]: SetTimer + cycle timer (PT1H) + interrupting stop event-subprocess +
  stop Task profile + singleton guard + restart-resume.
- **C — incremental watermark + bulk-on-first-sight + force-bulk** [B].
- **D — error-handling hardening** [A; finish after B/C].
- **E — second site dic2** [A, B].

## Locked design decisions

- **Topology:** single-site DIC-local; external ATMP register REST API (not DSF). One running instance per DIC.
- **Register contract (confirmed by platform email):** `POST {apiUrl}/api/medic-import`, body = FHIR `Bundle`
  `type collection` of bare Observations. **Upsert by `Observation.id`** → re-sends harmless (must send
  **stable id**, confirmed sites have them). `subject.reference = Patient/<pseudonym>` must pre-exist at the register
  or request errors. Auth header `MEDIC-API-KEY` = base64 `{"bhz":…,"apiKey":…}`, **one per endpoint/BHZ**.
  Staging `https://staging.app.integrate-atmp.de`.
- **Run model:** self-recurring timer loop, default **PT1H** (Task input override); clean stop via
  `atmpDataTransferStop`; singleton guard; resume after BPE restart (Camunda persists process vars).
- **Selection:** configured **LOINC list** + **`status=final`**. **Global watermark** = last-cycle time
  (minus small buffer for clock skew; overlap absorbed by upsert). **Bulk-on-first-sight** (`Set<pseudonym>`)
  handles late enrollment (ETL adds ResearchSubject after labs exist). **force-bulk** = one-shot full resend.
- **Outgoing transform:** per-subject `collection` bundle; rewrite subject→pseudonym; **keep** local
  `Observation.identifier` (to finalize w/ institution); minimize fields; preserve source id.
- **Errors:** per-subject isolation (don't advance state, retry next cycle), skip-tick on whole-cycle failure,
  audit to start-Task `output`. Never terminate loop.
- **Naming:** module/`getName()` `atmp-dsf`, groupId `de.ukhd`, package `de.ukhd.process.atmp`,
  own `http://ukhd.de/...` artifact URLs + `ConstantsAtmp` (name base `ukhdde_`). Reuse `mii-processes-common`
  utilities only.
- **Authorization:** `LOCAL_ROLE` (DIC) + `LOCAL_ROLE_PRACTITIONER` (DSF_ADMIN) requester, `LOCAL_ROLE`
  recipient, parent-org **`medizininformatik-initiative.de`**, **no `REMOTE_ROLE`**. (Reusing MII parent-org =
  zero per-site allow-list onboarding; one line to change if a dedicated ATMP consortium is ever required.)
- **Config:** env `…atmp.api.url`, `…atmp.observation.loinc.codes`, `…atmp.study.identifier.system/.value`
  (default `ATMP`); **docker-secret file** `…atmp.api.key.file`. Task inputs: `timer-interval`, `force-bulk`.
- **Test seams (3):** pure `ObservationBundleFactory` unit test (like NCT `TransactionBundleFactoryTest`);
  `fhir/profile/{TaskProfileTest, ActivityDefinitionProfileTest}`; `AtmpProcessPluginDefinitionTest`.
  E2E via Mockoon = manual in v1.
- **Test-setup:** install on **dic1** only (dic2 = Issue E); Mockoon register mock for `/api/medic-import`; seed the
  store via HTTP POST; **HAPI locally, Blaze in prod** (FHIR R4 search, config-only switch).

## Reference plugins (in-repo, read these when implementing)

- **`mii-process-report`** — the v2 reference. `report-autostart.bpmn` = the timer-loop + start/stop pattern.
  v2 services implement `dev.dsf.bpe.v2.activity.ServiceTask` (`execute(api, variables)`); `SetTimer` shows the
  Task-input→process-variable→`<timeDuration>` mechanism. Test prior art under `src/test/.../bpe` + `fhir/profile`.
- **`nct-process-data-transfer`** — v1, but the closest functional analog (collect → pseudonymize → build bundle
  → REST send). `mdat/TransactionBundleFactory` (+ `fhir/mdat/TransactionBundleFactoryTest`) = the pure-unit
  pattern for `ObservationBundleFactory`. `client/AbstractRestClient` = REST client shape. NCT uses a non-MII
  package yet reuses `mii-processes-common` — proof the namespace/reuse split works. Note: NCT is v1
  (`AbstractServiceDelegate`+`doExecute`); port idioms to v2.

## Open items / to confirm
- With the register/endpoint institution: that `/api/medic-import` accepts the per-subject `collection` bundle and its
  per-entry vs whole-request error behaviour; final decision on forwarding local `Observation.identifier` (kept for now).
- Toolchain: Java 25 + `github-mii` `read:packages` token must be available to build.

## Next command
Fresh session → `/implement` with `atmp-dsf/docs/atmp-dsf-prd.md` + `atmp-dsf/docs/issues/issue-A-one-shot-end-to-end-send.md`.
