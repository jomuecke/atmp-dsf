# Review: Issue B - Timer loop + clean stop

Reviewed against: `docs/issues/issue-B-timer-loop-and-clean-stop.md`

## Verdict

Partially implemented. The timer loop, stop Task resources, stop authorization, and structural tests are present, but the singleton guard acceptance criterion is not implemented.

## Findings

### High: Duplicate start rejection is missing

Issue B requires a second `atmpDataTransferStart` for the same study to be rejected while an instance is running.

Current state:

- `src/main/resources/bpe/atmp-data-transfer.bpmn` still has a plain `atmpDataTransferStart` message start event that can create another process instance.
- No guard service checks for an existing running process instance.
- No fixed or derived business key is enforced for the study.
- No test covers duplicate start rejection.

Risk: two long-lived process instances can run in parallel for the same study and double-send data to MEDIC.

Relevant files:

- `src/main/resources/bpe/atmp-data-transfer.bpmn`
- `src/main/resources/fhir/StructureDefinition/task-atmp-data-transfer-start.xml`

## Acceptance Checklist

- [x] `StructureDefinition/task-atmp-data-transfer-stop` authored.
- [x] Stop Task resources declared in `AtmpProcessPluginDefinition`.
- [x] `atmpDataTransferStop` wired as interrupting event subprocess.
- [x] Stop authorization mirrors local-only start authorization in `ActivityDefinition`.
- [x] `timer-interval` Task input is read by `SetTimer`.
- [x] Default timer interval is `PT1H` when absent.
- [x] BPMN loops on intermediate timer and re-enters subject querying.
- [ ] Duplicate start is rejected while an instance runs.
- [x] Restart resume is structurally supported by persisted BPMN process variables/timer wait.
- [x] Profile test covers stop Task profile.
- [x] Plugin definition test covers new resources and BPMN loop/stop structure.
- [ ] Demo against Mockoon/redeploy was not verified in this review.

## Open Question

Does DSF correlate `atmpDataTransferStop` Tasks to the running event subprocess without a business key?

The stop example Task has no business key, and `correlation-key` is disallowed. If the framework does not correlate uniquely by message name and process instance, the stop Task may need a required business key or a report-style stop-start/signal pattern.

## Verification

Command run:

```sh
mvn test
```

Result:

- 16 tests passed
- 0 failures
- 0 errors

