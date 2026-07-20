# ATMP Process Deployment v1.0.x.x

This page describes how to deploy the ATMP DSF process plugin into a DSF BPE.

The plugin is installed at each participating DIC. Each site runs its own process instance and sends data under its own register API key.

## Requirements

- DSF BPE compatible with DSF process API v2.
- Java runtime compatible with the plugin build target.
- Network access from the BPE to the local clinical FHIR store.
- Network access from the BPE to the ATMP register (integrate-ATMP) API.
- A site-specific `MEDIC-API-KEY` stored as a secret file.
- A configured FHIR client connection for the local clinical FHIR store.

## Build the Plugin

Build the plugin jar with Maven:

```sh
mvn package
```

The generated jar is written to `target/`.

For the local MII test setup, the `copy-to-test-setup` profile copies the jar into the configured test setup locations:

```sh
mvn package -Pcopy-to-test-setup
```

The current profile copies the plugin to the `dic1` and `dic2` BPE process directories in `../mii-processes-test-setup`.

## Install the Plugin

Copy the generated plugin jar into the process/plugin directory of the target DSF BPE.

In the Docker-based test setup this is the site's BPE `process` directory.

After copying the jar, restart or redeploy the DSF BPE so the process model and FHIR artifacts are loaded.

## Deployed Process Artifacts

The plugin registers the following artifacts:

- Process model: `ukhdde_atmpDataTransfer`
- Process URL: `http://ukhd.de/bpe/Process/atmpDataTransfer`
- Start message: `atmpDataTransferStart`
- Stop message: `atmpDataTransferStop`
- ActivityDefinition: `http://ukhd.de/fhir/ActivityDefinition/atmp-data-transfer`
- Start Task profile: `http://ukhd.de/fhir/StructureDefinition/task-atmp-data-transfer-start`
- Stop Task profile: `http://ukhd.de/fhir/StructureDefinition/task-atmp-data-transfer-stop`
- CodeSystem: `http://ukhd.de/fhir/CodeSystem/atmp-data-transfer`
- ValueSet: `http://ukhd.de/fhir/ValueSet/atmp-data-transfer`

## Docker Configuration

Add the required ATMP environment variables to the BPE deployment configuration.

Mount the register API key as a secret file and point `DE_UKHD_ATMP_API_KEY_FILE` to that file.

Example:

```yaml
services:
  dic-bpe:
    environment:
      DE_UKHD_ATMP_FHIR_SERVER_ID: dic-fhir-store
      DE_UKHD_ATMP_API_URL: https://staging.app.integrate-atmp.de
      DE_UKHD_ATMP_API_KEY_FILE: /run/secrets/atmp_medic_api_key
      DE_UKHD_ATMP_OBSERVATION_LOINC_CODES: 718-7,26464-8
      DE_UKHD_ATMP_STUDY_IDENTIFIER_VALUE: ATMP
      DE_UKHD_ATMP_TIMER_INTERVAL: PT1H
      DE_UKHD_ATMP_WATERMARK_BUFFER: PT1M
    secrets:
      - atmp_medic_api_key
```

The exact service name and secret declaration depend on the local DSF deployment.

## Local Clinical FHIR Store

The ATMP plugin reads from the local clinical FHIR store, not from the DSF FHIR endpoint.

The local clinical FHIR store must be configured as a FHIR client in the DSF BPE.

The value of `DE_UKHD_ATMP_FHIR_SERVER_ID` must match the configured client id.

The store can be HAPI or Blaze as long as it supports the required FHIR R4 searches.
