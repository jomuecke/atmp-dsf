# ATMP Process Configuration v1.0.x.x

The ATMP process is configured using BPE environment variables.

Add the required variables to the BPE deployment configuration of each participating site.

## Mandatory

### DE_UKHD_ATMP_FHIR_SERVER_ID

- Property: `de.ukhd.atmp.fhir.server.id`
- Required: Yes
- Processes: `ukhdde_atmpDataTransfer`
- Description: The id of the configured local clinical FHIR store client.
- Example: `dic-fhir-store`

This is not the DSF FHIR endpoint. It must point to the FHIR store that contains the ATMP `ResearchStudy`, `ResearchSubject`, `Patient` and `Observation` resources.

### DE_UKHD_ATMP_API_URL

- Property: `de.ukhd.atmp.api.url`
- Required: Yes
- Processes: `ukhdde_atmpDataTransfer`
- Description: Base URL of the ATMP register (integrate-ATMP) REST API.
- Example: `https://staging.app.integrate-atmp.de`

The process sends bundles to:

```text
{apiUrl}/api/medic-import
```

### DE_UKHD_ATMP_API_KEY_FILE

- Property: `de.ukhd.atmp.api.key.file`
- Required: Yes
- Processes: `ukhdde_atmpDataTransfer`
- Description: File containing the register API key for this site.
- Recommendation: Use a Docker secret file.
- Example: `/run/secrets/atmp_medic_api_key`

The file content is sent as the `MEDIC-API-KEY` HTTP header.

Do not put the API key into a start Task.

### DE_UKHD_ATMP_OBSERVATION_LOINC_CODES

- Property: `de.ukhd.atmp.observation.loinc.codes`
- Required: Yes
- Processes: `ukhdde_atmpDataTransfer`
- Description: Comma-separated list of LOINC codes to transfer.
- Example: `718-7,26464-8`

There is no built-in default list.

The plugin only sends Observations that match both conditions:

- `Observation.status = final`
- `Observation.code` contains one of the configured LOINC codes

If this property is missing or blank, plugin startup fails.

## Optional

### DE_UKHD_ATMP_STUDY_IDENTIFIER_SYSTEM

- Property: `de.ukhd.atmp.study.identifier.system`
- Required: No
- Processes: `ukhdde_atmpDataTransfer`
- Description: Identifier system used to find the ATMP `ResearchStudy`.
- Example: `https://example.org/fhir/sid/study`

Leave empty to search by identifier value only.

### DE_UKHD_ATMP_STUDY_IDENTIFIER_VALUE

- Property: `de.ukhd.atmp.study.identifier.value`
- Required: No
- Processes: `ukhdde_atmpDataTransfer`
- Description: Identifier value used to find the ATMP `ResearchStudy`.
- Default: `ATMP`
- Example: `ATMP`

The process searches the local clinical FHIR store for a `ResearchStudy` with this identifier.

### DE_UKHD_ATMP_TIMER_INTERVAL

- Property: `de.ukhd.atmp.timer.interval`
- Required: No
- Processes: `ukhdde_atmpDataTransfer`
- Description: Default ISO-8601 timer interval used when the start Task has no `timer-interval` input.
- Default: `PT1H`
- Example: `PT15M`

This value can be overridden per process instance by the `timer-interval` input of the start Task.

### DE_UKHD_ATMP_WATERMARK_BUFFER

- Property: `de.ukhd.atmp.watermark.buffer`
- Required: No
- Processes: `ukhdde_atmpDataTransfer`
- Description: ISO-8601 duration subtracted from the watermark for incremental queries.
- Default: `PT1M`
- Example: `PT5M`

The buffer absorbs clock skew between the BPE and the local FHIR store.

Overlapping re-sends are acceptable because the register upserts by `Observation.id`.

### DE_UKHD_ATMP_API_CONNECT_TIMEOUT

- Property: `de.ukhd.atmp.api.connect.timeout`
- Required: No
- Processes: `ukhdde_atmpDataTransfer`
- Description: ISO-8601 timeout for establishing the connection to the register API.
- Default: `PT10S`
- Example: `PT10S`

### DE_UKHD_ATMP_API_REQUEST_TIMEOUT

- Property: `de.ukhd.atmp.api.request.timeout`
- Required: No
- Processes: `ukhdde_atmpDataTransfer`
- Description: ISO-8601 timeout for a complete register API request/response.
- Default: `PT60S`
- Example: `PT60S`

Without this bound, a hung or very slow register would block the cycle's job thread indefinitely. A
timeout is treated as an unreachable register: the current cycle is aborted and the loop retries on
the next interval.

## Task Inputs

The start Task can provide process-instance-specific inputs.

### timer-interval

- CodeSystem: `http://ukhd.de/fhir/CodeSystem/atmp-data-transfer`
- Code: `timer-interval`
- Type: `string`
- Required: No
- Example: `PT15M`

This input overrides `DE_UKHD_ATMP_TIMER_INTERVAL` for the started instance.

### force-bulk

- CodeSystem: `http://ukhd.de/fhir/CodeSystem/atmp-data-transfer`
- Code: `force-bulk`
- Type: `boolean`
- Required: No
- Example: `true`

If set to `true`, the first cycle performs a full un-watermarked send for every subject.

After that first cycle, incremental mode resumes.
