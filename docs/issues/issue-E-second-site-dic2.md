# Issue E — Second site (dic2 / second BHZ)

Parent: `docs/atmp-dsf-prd.md`
Label: ready-for-agent

## What to build

Prove multi-site distribution by installing and running the plugin on a second test endpoint (`dic2`)
with its own BHZ identity, confirming the same artefact works unchanged across sites and that each
site sends under its own credentials.

- Add the plugin install to `dic2` in the test-setup (jar via the `copy-to-test-setup` profile + its
  own `MEDIC-API-KEY` secret + env).
- Seed `dic2-fhir-store` with its own study/subjects/patients/observations.
- Confirm both sites run independently against the Mockoon MEDIC mock, each using a distinct
  `MEDIC-API-KEY` (distinct BHZ), with no per-site code or authorization changes.

## Acceptance criteria

- [ ] `copy-to-test-setup` profile installs the jar to `dic2` as well as `dic1`.
- [ ] `dic2` has its own API-key secret and env; its store is seeded independently.
- [ ] Both `dic1` and `dic2` can run the loop concurrently and send to the Mockoon mock under their
      own `MEDIC-API-KEY` (the mock can distinguish the two BHZ identities).
- [ ] No plugin code or `ActivityDefinition` authorization change was needed to add the second site.
- [ ] Demoable: start the process on both sites; observe bundles arriving for each BHZ at the mock.

## Blocked by

- Issue A — One-shot end-to-end send.
- Issue B — Timer loop + clean stop.
