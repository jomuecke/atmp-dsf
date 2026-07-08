# ATMP Process Description v1.0.x.x

The ATMP DSF process plugin transfers laboratory data for ATMP study participants from a participating DIC to the external MEDIC / integrate-ATMP REST API.

The process runs in the local DSF BPE of the participating DIC. It reads from the site's local clinical FHIR store and sends pseudonymized FHIR `Observation` bundles to MEDIC.

It is important to distinguish between the DSF FHIR server and the local clinical FHIR store.

The DSF FHIR server contains the DSF process resources and Tasks used to start and stop the process. The local clinical FHIR store contains the actual ATMP study data to be transferred.

## Topology

```text
Local clinical FHIR store        Local DSF BPE                  MEDIC REST API
ResearchStudy             -->   ATMP process loop        -->   POST /api/medic-import
ResearchSubject                 query subjects
Patient                         query Observations
Observation                     pseudonymize and minimize
```

The MEDIC endpoint is an external REST API. This process is not a DSF-to-DSF transfer.

There is no DMS, no receive-side DSF process, no DSF message exchange with MEDIC, no public/private key exchange, and no encrypted bundle retrieval by a remote DSF endpoint.

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
- Send the bundle to MEDIC with the `MEDIC-API-KEY` header.

The first time a subject is seen by a running process instance, the process sends all matching Observations for that subject.

After that, the process uses an incremental `_lastUpdated` query based on the stored process watermark.

Newly enrolled subjects are picked up automatically because the process queries `ResearchSubject` resources on every cycle.

## Outgoing Bundle

The outgoing request body is a FHIR R4 `Bundle` with `type=collection`.

Each bundle contains the matching `Observation` resources for one ATMP study participant.

The source `Observation.id` is kept unchanged. MEDIC uses this id for upsert behavior, so re-sends update existing records instead of creating duplicates.

The local patient reference is not sent. The outgoing subject reference is always:

```text
Patient/<ATMP pseudonym>
```

The ATMP pseudonym is read from `ResearchSubject.identifier.value`.

## Error Handling

Subject-level failures are isolated. If one subject fails, the process logs and audits the error and continues with the next subject.

The failed subject is not marked as successfully handled and is retried in a later cycle.

Errors are appended to the start Task output with audit details such as pseudonym, timestamp and cause.

The MEDIC API key and local patient identity must not be written to logs or Task output.
