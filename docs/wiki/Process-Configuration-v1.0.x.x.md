# ATMP Process Configuration v1.0.x.x

The ATMP process is configured using BPE environment variables.

Add the required variables to the BPE deployment configuration of each participating site.

## Environment Variables

<!-- BEGIN GENERATED: copied from target/ConfigDoc_de.ukhd.process.atmp.spring.config.md (mvn package).
     Do not edit by hand. Change the @ProcessDocumentation annotations in AtmpConfig.java and re-copy. -->

### DE_UKHD_ATMP_API_BHZ
- **Property:** de.ukhd.atmp.api.bhz
- **Required:** No
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** BHZ identity used with the raw register API key to construct the Base64 MEDIC-API-KEY header; leave empty only when the configured key file already contains the complete encoded header value
- **Example:** `bhz-demo`


### DE_UKHD_ATMP_API_CONNECT_TIMEOUT
- **Property:** de.ukhd.atmp.api.connect.timeout
- **Required:** No
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** ISO-8601 timeout for establishing the connection to the register API
- **Example:** `PT10S`
- **Default:** `PT10S`


### DE_UKHD_ATMP_API_KEY_FILE
- **Property:** de.ukhd.atmp.api.key.file
- **Required:** Yes
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** Location of a docker-secret file containing the raw register API key when a BHZ is configured, or the complete encoded MEDIC-API-KEY header value in legacy mode
- **Recommendation:** Configure a BHZ and store only the raw API key in this file
- **Example:** `/run/secrets/atmp_medic_api_key`


### DE_UKHD_ATMP_API_MTLS_CERTIFICATE_CHAIN_FILE
- **Property:** de.ukhd.atmp.api.mtls.certificate.chain.file
- **Required:** Yes
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** PEM file with the site's ATMP client certificate followed by its intermediate CA certificates
- **Example:** `/run/secrets/atmp_register_client_certificate.pem`


### DE_UKHD_ATMP_API_MTLS_PRIVATE_KEY_FILE
- **Property:** de.ukhd.atmp.api.mtls.private.key.file
- **Required:** Yes
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** PEM file with the private key of the ATMP client certificate
- **Example:** `/run/secrets/atmp_register_client_certificate_private_key.pem`


### DE_UKHD_ATMP_API_MTLS_PRIVATE_KEY_PASSWORD_FILE
- **Property:** de.ukhd.atmp.api.mtls.private.key.password.file
- **Required:** No
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** Secret file with the password of an encrypted mTLS private key; one terminal line ending is ignored, all other characters are part of the password
- **Example:** `/run/secrets/atmp_register_client_certificate_private_key.pem.password`


### DE_UKHD_ATMP_API_MTLS_TRUSTED_SERVER_CAS_FILE
- **Property:** de.ukhd.atmp.api.mtls.trusted.server.cas.file
- **Required:** Yes
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** PEM file with the CA certificates trusted to issue the register server certificate; replaces the JVM default trust for the register connection
- **Example:** `/run/secrets/atmp_register_server_ca.pem`


### DE_UKHD_ATMP_API_REQUEST_TIMEOUT
- **Property:** de.ukhd.atmp.api.request.timeout
- **Required:** No
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** ISO-8601 timeout for a complete register API request/response; without it a hung register would block the cycle indefinitely
- **Example:** `PT60S`
- **Default:** `PT60S`


### DE_UKHD_ATMP_API_URL
- **Property:** de.ukhd.atmp.api.url
- **Required:** Yes
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** Complete URL of the MEDIC / integrate-ATMP endpoint that accepts collection Bundles; the process posts to this exact URL without appending a path; must be https
- **Example:** `https://medic-staging.dkfz.de/medic-import`


### DE_UKHD_ATMP_FHIR_SERVER_ID
- **Property:** de.ukhd.atmp.fhir.server.id
- **Required:** Yes
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** The id of the local (non DSF) FHIR server holding the ATMP study data, configured in the DSF BPE FHIR client connections config (HAPI or Blaze)
- **Example:** `dic-fhir-store`


### DE_UKHD_ATMP_OBSERVATION_LOINC_CODES
- **Property:** de.ukhd.atmp.observation.loinc.codes
- **Required:** Yes
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** Comma-separated list of LOINC codes of the laboratory Observations to transfer
- **Example:** `718-7,26464-8`


### DE_UKHD_ATMP_STUDY_IDENTIFIER_SYSTEM
- **Property:** de.ukhd.atmp.study.identifier.system
- **Required:** No
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** Identifier system of the ATMP ResearchStudy in the local FHIR store, leave empty to match by identifier value only
- **Example:** `https://ukhd.de/fhir/sid/research-study`


### DE_UKHD_ATMP_STUDY_IDENTIFIER_VALUE
- **Property:** de.ukhd.atmp.study.identifier.value
- **Required:** No
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** Identifier value of the ATMP ResearchStudy in the local FHIR store
- **Example:** `ATMP`
- **Default:** `ATMP`


### DE_UKHD_ATMP_TIMER_INTERVAL
- **Property:** de.ukhd.atmp.timer.interval
- **Required:** No
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** Default ISO-8601 timer interval used when the start Task has no timer-interval input
- **Example:** `PT1H`
- **Default:** `PT1H`


### DE_UKHD_ATMP_WATERMARK_BUFFER
- **Property:** de.ukhd.atmp.watermark.buffer
- **Required:** No
- **Processes:** ukhdde_atmpDataTransfer
- **Description:** ISO-8601 duration subtracted from the watermark on incremental cycles to absorb BPE/FHIR-store clock skew; harmless overlap is absorbed by the register's upsert-by-id
- **Example:** `PT1M`
- **Default:** `PT1M`

<!-- END GENERATED -->
