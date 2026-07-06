# Issue B — Timer loop + clean stop

Parent: `docs/atmp-dsf-prd.md`
Label: ready-for-agent

## What to build

Turn the one-shot process from Issue A into a long-lived, self-recurring loop with a clean stop,
mirroring `mii-process-report`'s `report-autostart.bpmn`.

- A `SetTimer` service reads the `timer-interval` Task input (else env/constant default **`PT1H`**)
  into a process variable that a BPMN **intermediate cycle timer** (`<timeDuration>`) consumes; after
  each pass the process waits the interval and loops back to querying subjects. The process has **no
  natural end**.
- Clean stop via an **interrupting message event-subprocess** listening for `atmpDataTransferStop`,
  which terminates the running instance immediately.
- **Singleton guard**: a second `atmpDataTransferStart` for the same study while an instance is
  running is rejected.
- **Restart resume**: the loop's process variables persist across a BPE crash/redeploy (Camunda),
  so the instance resumes on the next interval without operator action.

## Acceptance criteria

- [ ] `StructureDefinition/task-atmp-data-transfer-stop` authored (same local-only authorization as
      start) and declared; `atmpDataTransferStop` wired as an interrupting event-subprocess.
- [ ] `timer-interval` Task input honoured; default `PT1H` when absent.
- [ ] BPMN loops on the cycle timer; sending still works each cycle (Issue A behaviour repeated).
- [ ] A duplicate start is rejected while an instance runs.
- [ ] After a BPE restart, the existing instance resumes (no duplicate instance, no manual restart).
- [ ] Profile test extended to the stop Task profile; plugin-def test covers the new resources.
- [ ] Demoable: process sends on each interval against Mockoon; `…Stop` halts it cleanly; redeploy resumes.

## Blocked by

- Issue A — One-shot end-to-end send.
