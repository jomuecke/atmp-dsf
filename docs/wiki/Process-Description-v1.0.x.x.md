# ATMP Process Description v1.0.x.x

The ATMP DSF process plugin transfers laboratory data for ATMP study participants from a participating DIC to the external ATMP register (integrate-ATMP) REST API.

The process runs in the local DSF BPE of the participating DIC. It reads from the site's local clinical FHIR store and sends pseudonymized FHIR `Observation` bundles to the register.

It is important to distinguish between the DSF FHIR server and the local clinical FHIR store.

The DSF FHIR server contains the DSF process resources and Tasks used to start and stop the process. The local clinical FHIR store contains the actual ATMP study data to be transferred.

## Topology

```text
Local clinical FHIR store        Local DSF BPE                  ATMP register REST API
ResearchStudy             -->   ATMP process loop        -->   POST /api/medic-import
ResearchSubject                 query subjects
Patient                         query Observations
Observation                     pseudonymize and minimize
```

The register endpoint is an external REST API. This process is not a DSF-to-DSF transfer.

There is no DMS, no receive-side DSF process, no DSF message exchange with the register, no public/private key exchange, and no encrypted bundle retrieval by a remote DSF endpoint.

## ATMP Data Transfer Process

The process is started by a local DSF Task using the BPMN message `atmpDataTransferStart`.

After start, the process runs as one long-lived process instance. It performs an initial transfer cycle, waits for the configured timer interval, and repeats until stopped.

A second start is rejected while another ATMP transfer instance is active.

The process can be stopped by a local DSF Task using the BPMN message `atmpDataTransferStop`.

## Transfer Cycle

Each cycle performs the following steps:

- Find the configured ATMP `ResearchStudy` in the local clinical FHIR store.
- Query all `ResearchSubject` resources for that study.
- Read each subject's local `Patient` reference and ATMP pseudonym.
- Query final laboratory `Observation` resources for the configured LOINC codes.
- Build one FHIR `Bundle` of `type=collection` per subject.
- Rewrite `Observation.subject.reference` to `Patient/<ATMP pseudonym>`.
- Preserve the source `Observation.id`.
- Preserve the local `Observation.identifier`.
- Remove fields that are not part of the agreed outgoing data shape.
- Send the bundle to the register with the `MEDIC-API-KEY` header.

The first time a subject is seen by a running process instance, the process sends all matching Observations for that subject.

After that, the process uses an incremental `_lastUpdated` query based on the stored process watermark.

Newly enrolled subjects are picked up automatically because the process queries `ResearchSubject` resources on every cycle.

## Outgoing Bundle

The outgoing request body is a FHIR R4 `Bundle` with `type=collection`.

Each bundle contains the matching `Observation` resources for one ATMP study participant.

The source `Observation.id` is kept unchanged. The register uses this id for upsert behavior, so re-sends update existing records instead of creating duplicates.

The local patient reference is not sent. The outgoing subject reference is always:

```text
Patient/<ATMP pseudonym>
```

The ATMP pseudonym is read from `ResearchSubject.identifier.value`.

### Register import schema

The register's import API validates a shape that is stricter than FHIR R4 itself. The bundle and every Observation are built to satisfy it:

- The bundle carries an `id` and a `meta.lastUpdated`.
- Each Observation keeps `meta.versionId`, `meta.lastUpdated` and `meta.source`, forwarded unchanged from the local store. Local `meta.profile`, `tag` and `security` are dropped.
- `effectiveDateTime` and every `meta.lastUpdated` carry an explicit numeric UTC offset (e.g. `+02:00`); a bare `Z`/Zulu suffix is rewritten to an offset because the register rejects `Z`.
- Each Observation must have a complete `valueQuantity` (value, unit, system, code) and a second-precision `effectiveDateTime`.

An Observation that cannot satisfy this — for example a local store that does not populate `meta.source`, a date-only `effectiveDateTime`, or a coded/component-only value — is **not** sent silently. It is left out of the bundle and reported in the start Task audit output as a rejected Observation, naming its id and the reason. The rest of the subject's Observations are still sent.

If a subject's pseudonym itself does not match the register's subject-reference pattern (`^Patient/[\w\d-]{0,30}$`), the whole subject fails and is retried, since every one of its Observations would be rejected.

## Error Handling

Failures are classified by blast radius.

**Subject-level failures are isolated.** If one subject fails — a rejected bundle (`400`/`E064`), an acknowledgement that could not be read, or a bundle-creation error — the process logs and audits the error and continues with the next subject. The failed subject is not marked as successfully handled and is retried in a later cycle.

**A `2xx` response is not treated as success on its own.** The register answers `201` with an acknowledgement that may still report per-entry problems: an unknown PID (a *skippable* failure), a non-skippable failure, or entries its own schema rejected (`parseIssues`). Any of these leaves the subject unhandled and audited so it is re-sent next cycle, rather than counting as delivered. In particular an unknown PID is backfilled automatically once the pseudonym is provisioned in the register — the subject keeps being re-sent in the meantime.

**Whole-cycle failures abort the remaining subjects for that cycle.** A register that is unreachable, times out, returns `5xx`, or rejects the site's `MEDIC-API-KEY` (`401`/`E060`–`E063`) fails identically for every subject. The cycle is aborted with a single audit entry, the watermark is not advanced, and the loop retries on the next interval. The process instance never terminates.

Errors are appended to the start Task output with audit details such as pseudonym, timestamp and cause.

The register API key and local patient identity must not be written to logs or Task output. The API key is only ever sent as a request header and never appears in an exception or audit entry; the local patient reference is redacted to `Patient/<pseudonym>` in audit output.
