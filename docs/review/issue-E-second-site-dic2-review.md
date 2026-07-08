# Review: Issue E - Second site (dic2 / second BHZ)

Reviewed against: `docs/issues/issue-E-second-site-dic2.md`

## Verdict

Partially implemented. The jar copy profile, dic2 BPE configuration, separate MEDIC API key, dic2 seed bundle, and Mockoon key distinction are present. The main risk is that the dic2 DSF FHIR endpoint remains on the base 1.9.0 image while the plugin and dic2 BPE are configured for DSF 2.1.0 / process API v2.

## Findings

### High: dic2 DSF FHIR endpoint is not upgraded to the plugin's DSF 2.1.0 runtime

The test-setup override upgrades `dic2-bpe` to `ghcr.io/datasharingframework/bpe:2.1.0`, but leaves the `dic2-fhir` override commented out. Therefore `dic2-fhir` inherits the base `docker-compose.yml` image `ghcr.io/datasharingframework/fhir:1.9.0`.

Issue E is meant to prove the same artifact works unchanged on the second DSF endpoint. With dic2 split across BPE 2.1.0 and FHIR 1.9.0, the dic2 endpoint is not equivalent to dic1 and may fail process/resource deployment or Task/profile handling for the v2 plugin resources.

Relevant files:

- `../mii-processes-test-setup/docker/docker-compose.override.yml`
- `../mii-processes-test-setup/docker/docker-compose.yml`

Expected direction: mirror dic1 and explicitly set `dic2-fhir` to `ghcr.io/datasharingframework/fhir:2.1.0` in the override.

## Test Gaps

- The Docker/e2e demo was not run in this review.
- There is no automated check that `docker compose config` resolves dic2 to matching DSF 2.1.0 FHIR+BPE images.
- There is no automated Mockoon assertion proving concurrent dic1/dic2 requests arrive under distinct `MEDIC-API-KEY` values.

## Acceptance Checklist

- [x] `copy-to-test-setup` profile installs the jar to `dic2` as well as `dic1`.
- [x] `dic2` has its own MEDIC API key secret and BPE env.
- [x] `dic2` has an independent seed bundle in `test/ressources/atmp_fhir_store_test_bundle_dic2.json`.
- [x] Mockoon distinguishes dic1 `UKHD` and dic2 `MRI` API keys.
- [ ] dic2 DSF endpoint is fully aligned to the plugin runtime (`dic2-fhir` still inherits DSF 1.9.0).
- [ ] Both `dic1` and `dic2` running concurrently was not verified in this review.
- [x] No plugin code or `ActivityDefinition` authorization change appears necessary for adding dic2.
- [ ] Demo against Mockoon was not verified in this review.

## Verification

Command run:

```sh
mvn test
```

Result:

- 36 tests passed
- 0 failures
- 0 errors

