# ATMP DSF process plugin

`atmp-dsf` is a DSF BPE process plugin that periodically collects selected laboratory Observations for participants
of a configured ATMP ResearchStudy and sends pseudonymized FHIR R4 collection Bundles to an integrate-ATMP register.

The process runs as a singleton timer loop. Its first successful cycle transfers all matching Observations. Later
cycles use an `_lastUpdated` watermark while newly enrolled subjects still receive a full first transfer. The process
audits rejected Observations and transfer failures on its start Task and retries work whose state was not safely
advanced.

## Compatibility

| Component | Version |
| --- | --- |
| DSF process API | `2.1.0` |
| FHIR | `R4` / `4.0.1` |
| Java | `25` |

The register endpoint must implement the request and acknowledgement contract documented in
[`medic-import.yaml`](medic-import.yaml). The configured URL is used exactly as supplied; the plugin does not append
an endpoint path.

## Installation

1. Download `atmp-dsf-<version>.jar` and `SHA256SUMS` from the desired GitHub release, where `<version>` is the
   selected release number.
2. Verify the artifact:

   ```shell
   sha256sum --check SHA256SUMS
   ```

3. Place the JAR in the process-plugin directory mounted into the DSF BPE container.
4. Configure the properties below and restart the BPE.

The JAR contains the BPMN model and the FHIR ActivityDefinition, CodeSystem, ValueSet, Task profiles, and start/stop
Task templates required by the process.

## Configuration

Configure these properties in the DSF BPE environment. Secret values should be mounted as files rather than committed
to deployment configuration.

| Property | Required | Default | Purpose |
| --- | --- | --- | --- |
| `de.ukhd.atmp.fhir.server.id` | yes | — | ID of the DSF-configured FHIR client for the local ATMP data store. |
| `de.ukhd.atmp.api.url` | yes | — | Complete integrate-ATMP collection-Bundle endpoint URL. |
| `de.ukhd.atmp.api.bhz` | recommended | empty | BHZ identity combined with the raw API key for the `MEDIC-API-KEY` header. |
| `de.ukhd.atmp.api.key.file` | yes | — | Path to the mounted file containing the raw API key. If BHZ is empty, the file must contain the complete encoded header value. |
| `de.ukhd.atmp.observation.loinc.codes` | yes | — | Comma-separated LOINC codes to transfer. |
| `de.ukhd.atmp.api.connect.timeout` | no | `PT10S` | ISO-8601 connection timeout. |
| `de.ukhd.atmp.api.request.timeout` | no | `PT60S` | ISO-8601 request/response timeout. |
| `de.ukhd.atmp.study.identifier.system` | no | empty | Identifier system of the local ATMP ResearchStudy. |
| `de.ukhd.atmp.study.identifier.value` | no | `ATMP` | Identifier value of the local ATMP ResearchStudy. |
| `de.ukhd.atmp.timer.interval` | no | `PT1H` | Default interval between transfer cycles. |
| `de.ukhd.atmp.watermark.buffer` | no | `PT1M` | Overlap subtracted from incremental-query watermarks to absorb clock skew. |

When a BHZ is configured, the key file contains only the raw secret. The plugin constructs and Base64-encodes the
following JSON as the `MEDIC-API-KEY` value:

```json
{"bhz":"configured-bhz","apiKey":"value-from-secret-file"}
```

Outbound requests automatically use the DSF proxy configuration when it is enabled for the register endpoint.

## Operation

Starting the packaged `atmpDataTransferStart` Task creates the long-lived transfer loop. An optional `timer-interval`
Task input overrides the configured default for that instance. A second concurrent start is rejected. Send the
packaged `atmpDataTransferStop` Task to terminate the active loop cleanly.

Only `final` Observations matching the configured LOINC codes are selected. Patient references in outgoing
Observations are replaced with the corresponding ResearchSubject pseudonym. Consult the start Task outputs and BPE
logs for rejected records or cycle-level failures.

## Building

The build requires Maven, Java 25, and read access to the MII GitHub Packages repository declared in `pom.xml`.

```shell
mvn --batch-mode --fail-at-end clean verify
```

The `verify` lifecycle runs the test suite, formatting and import checks, PMD, and SpotBugs. The resulting plugin is
written to `target/atmp-dsf-<version>.jar`.

## License

Licensed under the [Apache License 2.0](LICENSE).
