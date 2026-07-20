# ATMP Process Preparation & Start v1.0.x.x

This page describes how to prepare the local systems and start or stop the ATMP process.

The DSF FHIR server and the local clinical FHIR store are different servers.

The DSF FHIR server receives the start and stop Tasks. The local clinical FHIR store contains the ATMP study data that is read by the process.

## 1. Prepare the ATMP Register

Before starting the process, the register must know the ATMP pseudonym patients used by the site.

For every outgoing Observation, the plugin writes:

```text
Observation.subject.reference = Patient/<ATMP pseudonym>
```

If the register does not know that pseudonym, the subject bundle may be rejected.

## 2. Prepare the Local Clinical FHIR Store

The local clinical FHIR store must contain the ATMP study data.

Required resources:

- One ATMP `ResearchStudy`
- `ResearchSubject` resources for enrolled participants
- A local `Patient` referenced by each `ResearchSubject.individual`
- The ATMP pseudonym in `ResearchSubject.identifier.value`
- Laboratory `Observation` resources for those Patients

The configured study identifier must match the local `ResearchStudy`.

The Observations must have:

- `status = final`
- a LOINC code configured in `DE_UKHD_ATMP_OBSERVATION_LOINC_CODES`
- `subject` pointing to the local Patient

## 3. Prepare DSF Authorization

The process is local-only.

It is intended to be started and stopped by the local DIC organization or a local DSF admin practitioner.

No remote DSF organization starts this process.

## 4. Start the Process

Create a Task conforming to:

```text
http://ukhd.de/fhir/StructureDefinition/task-atmp-data-transfer-start
```

The Task must contain the BPMN message input:

```text
atmpDataTransferStart
```

Example start Task:

```xml
<Task xmlns="http://hl7.org/fhir">
  <meta>
    <profile value="http://ukhd.de/fhir/StructureDefinition/task-atmp-data-transfer-start|1.0.0.0"/>
  </meta>
  <instantiatesCanonical value="http://ukhd.de/bpe/Process/atmpDataTransfer|1.0.0.0"/>
  <status value="requested"/>
  <intent value="order"/>
  <authoredOn value="2026-07-08"/>
  <requester>
    <type value="Organization"/>
    <identifier>
      <system value="http://dsf.dev/sid/organization-identifier"/>
      <value value="<REPLACE-WITH-LOCAL-DIC-IDENTIFIER>"/>
    </identifier>
  </requester>
  <restriction>
    <recipient>
      <type value="Organization"/>
      <identifier>
        <system value="http://dsf.dev/sid/organization-identifier"/>
        <value value="<REPLACE-WITH-LOCAL-DIC-IDENTIFIER>"/>
      </identifier>
    </recipient>
  </restriction>
  <input>
    <type>
      <coding>
        <system value="http://dsf.dev/fhir/CodeSystem/bpmn-message"/>
        <code value="message-name"/>
      </coding>
    </type>
    <valueString value="atmpDataTransferStart"/>
  </input>
  <input>
    <type>
      <coding>
        <system value="http://ukhd.de/fhir/CodeSystem/atmp-data-transfer"/>
        <version value="1.0.0.0"/>
        <code value="timer-interval"/>
      </coding>
    </type>
    <valueString value="PT1H"/>
  </input>
  <input>
    <type>
      <coding>
        <system value="http://ukhd.de/fhir/CodeSystem/atmp-data-transfer"/>
        <version value="1.0.0.0"/>
        <code value="force-bulk"/>
      </coding>
    </type>
    <valueBoolean value="false"/>
  </input>
</Task>
```

The repository also contains an example start Task:

```text
src/main/resources/fhir/Task/task-atmp-data-transfer-start.xml
```

Submit the Task to the local DSF FHIR endpoint.

Example:

```sh
curl \
  -H "Accept: application/fhir+xml" \
  -H "Content-Type: application/fhir+xml" \
  -d @task-atmp-data-transfer-start.xml \
  https://<dsf-fhir-base-url>/fhir/Task
```

Replace `<dsf-fhir-base-url>` with the local DSF FHIR endpoint.

## 5. Running Behavior

After start, the process performs the first transfer cycle immediately.

It then waits for the configured timer interval and repeats until stopped.

The process stores the watermark and seen-subject list as process variables.

After a BPE restart or redeploy, an existing process instance should resume from the persisted timer and process variables.

If another ATMP process instance is already active, a duplicate start is rejected and does not enter the transfer loop.

## 6. Stop the Process

Create a Task conforming to:

```text
http://ukhd.de/fhir/StructureDefinition/task-atmp-data-transfer-stop
```

The Task must contain the BPMN message input:

```text
atmpDataTransferStop
```

Example stop Task:

```xml
<Task xmlns="http://hl7.org/fhir">
  <meta>
    <profile value="http://ukhd.de/fhir/StructureDefinition/task-atmp-data-transfer-stop|1.0.0.0"/>
  </meta>
  <instantiatesCanonical value="http://ukhd.de/bpe/Process/atmpDataTransfer|1.0.0.0"/>
  <status value="requested"/>
  <intent value="order"/>
  <authoredOn value="2026-07-08"/>
  <requester>
    <type value="Organization"/>
    <identifier>
      <system value="http://dsf.dev/sid/organization-identifier"/>
      <value value="<REPLACE-WITH-LOCAL-DIC-IDENTIFIER>"/>
    </identifier>
  </requester>
  <restriction>
    <recipient>
      <type value="Organization"/>
      <identifier>
        <system value="http://dsf.dev/sid/organization-identifier"/>
        <value value="<REPLACE-WITH-LOCAL-DIC-IDENTIFIER>"/>
      </identifier>
    </recipient>
  </restriction>
  <input>
    <type>
      <coding>
        <system value="http://dsf.dev/fhir/CodeSystem/bpmn-message"/>
        <code value="message-name"/>
      </coding>
    </type>
    <valueString value="atmpDataTransferStop"/>
  </input>
</Task>
```

The repository also contains an example stop Task:

```text
src/main/resources/fhir/Task/task-atmp-data-transfer-stop.xml
```

Submit the stop Task to the local DSF FHIR endpoint.

Example:

```sh
curl \
  -H "Accept: application/fhir+xml" \
  -H "Content-Type: application/fhir+xml" \
  -d @task-atmp-data-transfer-stop.xml \
  https://<dsf-fhir-base-url>/fhir/Task
```

The interrupting stop message terminates the running process instance.

## 7. Monitoring and Troubleshooting

Check the DSF BPE logs for transfer progress and error messages.

Subject-level failures are appended to the start Task output.

Common problems:

- Register API URL is not reachable from the BPE container.
- `MEDIC-API-KEY` file is missing, empty or wrong.
- The register does not know the ATMP pseudonym Patient.
- The local FHIR store client id is wrong.
- The configured `ResearchStudy` identifier does not match local data.
- The configured LOINC list does not match the available Observations.
- Observations are not `final` and are therefore skipped.
