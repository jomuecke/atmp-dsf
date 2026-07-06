# Issue A — One-shot end-to-end send (tracer bullet)

Parent: `docs/atmp-dsf-prd.md`
Label: ready-for-agent

## What to build

The first complete path through every layer of the ATMP plugin, with the simplest possible
behaviour: an operator POSTs a start Task to the local DIC's DSF FHIR endpoint, the process queries
the local FHIR store for the ATMP study's `ResearchSubject`s, collects each subject's matching
laboratory `Observation`s, pseudonymizes and minimizes them, and POSTs them as a per-subject FHIR
collection bundle to the (mocked) MEDIC REST API. No timer loop and no incremental watermarking yet —
this slice **sends all matching Observations once and ends**.

End-to-end behaviour:
- Started by message `atmpDataTransferStart`; authorized to the local DIC org / DSF_ADMIN
  practitioner under parent-org `medizininformatik-initiative.de` (no remote requester).
- For each `ResearchSubject` of the configured ATMP `ResearchStudy`: resolve its `Patient`, query
  that patient's `Observation`s filtered by the configured **LOINC code list** + **`status=final`**.
- Build a `Bundle` of `type collection` per subject: rewrite `subject.reference` →
  `Patient/<pseudonym>` (from `ResearchSubject.identifier.value`), **preserve the source
  `Observation.id`**, keep the local `Observation.identifier`, minimize to id/status/category/code/
  subject/effective[x]/value[x]/component.
- POST each bundle to `{apiUrl}/api/medic-import` with header `MEDIC-API-KEY`.
- The collect-and-transform logic lives in a pure `ObservationBundleFactory`; the BPMN-wired v2
  `ServiceTask`s (query, send) are thin adapters.

Config: env `…atmp.api.url`, `…atmp.observation.loinc.codes`, `…atmp.study.identifier.system`/`…value`
(default `ATMP`); docker-secret file `…atmp.api.key.file`. Local FHIR store is HAPI or Blaze by config.

References in-repo: `nct-process-data-transfer` (REST client, `TransactionBundleFactory` +
`TransactionBundleFactoryTest`), `mii-process-report` (v2 `ServiceTask`, profile/plugin-def tests).

## Acceptance criteria

- [ ] `AtmpProcessPluginDefinition` declares the BPMN model and the FHIR resources; the plugin loads.
- [ ] FHIR artifacts authored under `http://ukhd.de/...`: `ActivityDefinition/atmp-data-transfer`,
      `StructureDefinition/task-atmp-data-transfer-start`, `CodeSystem` + `ValueSet`
      `atmp-data-transfer`, with MII-DIC local-only authorization (requester `LOCAL_ROLE` +
      `LOCAL_ROLE_PRACTITIONER` DSF_ADMIN, recipient `LOCAL_ROLE`, no `REMOTE_ROLE`).
- [ ] `ObservationBundleFactory` produces, for given subjects + observations: correct selection
      (LOINC + `status=final`), subject ref rewritten to the pseudonym, source `Observation.id`
      preserved, local identifier kept, fields minimized, one `collection` bundle per subject.
- [ ] `MedicClient` POSTs to `/api/medic-import` with the `MEDIC-API-KEY` header from the secret file.
- [ ] Minimal BPMN: start → query subjects → multi-instance per subject → build → POST → end.
- [ ] Tests pass: `ObservationBundleFactory` unit test (sample FHIR in → asserted bundle out, mirroring
      `TransactionBundleFactoryTest`); `fhir/profile/{TaskProfileTest, ActivityDefinitionProfileTest}`;
      `AtmpProcessPluginDefinitionTest`.
- [ ] Test-setup: Mockoon mock for `POST /api/medic-import` (asserts the header), dic1 install
      (jar + secret + env via compose override), study/subject/patient/observation seed bundle loaded
      into `dic1-fhir-store` via HTTP POST.
- [ ] Demoable: POST the start Task → a pseudonymized collection bundle is observed at the Mockoon mock.

## Blocked by

None - can start immediately. (Builds on the Step-1 scaffold already in `main`.)
