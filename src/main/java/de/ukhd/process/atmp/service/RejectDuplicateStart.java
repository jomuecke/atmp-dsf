package de.ukhd.process.atmp.service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.ukhd.process.atmp.ConstantsAtmp;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.variables.Variables;

/**
 * Rejects duplicate starts of the long-lived ATMP transfer loop. The DSF FHIR server is queried for other
 * {@code in-progress} start Tasks with the same (versioned) start Task profile: the own start Task is committed as
 * {@code in-progress} before the process instance runs, so any other in-progress start Task means a second loop.
 * Ordering by ({@code authoredOn}, Task id) decides which instance survives — the oldest wins — so two
 * near-simultaneous starts cannot reject each other and leave zero loops running.
 * <p>
 * Note: a crashed instance whose start Task was never completed (still {@code in-progress}) blocks new starts; the
 * stale Task must be stopped via {@code atmpDataTransferStop} or its status corrected on the DSF FHIR server.
 */
public class RejectDuplicateStart implements ServiceTask
{
	private static final Logger logger = LoggerFactory.getLogger(RejectDuplicateStart.class);

	@Override
	public void execute(ProcessPluginApi api, Variables variables) throws Exception
	{
		Task startTask = variables.getStartTask();
		String startTaskId = startTask.getIdElement().getIdPart();
		String profile = startProfile(startTask);

		Bundle result = api.getDsfClientProvider().getLocal().search(Task.class, Map.of("status",
				List.of(Task.TaskStatus.INPROGRESS.toCode()), "_profile", List.of(profile), "_count", List.of("100")));

		List<Task> otherInProgress = result.getEntry().stream().map(Bundle.BundleEntryComponent::getResource)
				.filter(r -> r instanceof Task).map(r -> (Task) r)
				.filter(t -> !Objects.equals(startTaskId, t.getIdElement().getIdPart())).toList();

		boolean duplicateStart = isDuplicateStart(startTask, otherInProgress);
		variables.setBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_DUPLICATE_START, duplicateStart);

		if (duplicateStart)
			logger.warn(
					"Rejected duplicate ATMP data transfer start (Task/{}): {} other in-progress start Task(s) found."
							+ " If no loop is actually running, stop or correct the stale in-progress Task on the DSF FHIR server",
					startTaskId, otherInProgress.size());
		else
			logger.info("ATMP data transfer singleton guard passed (Task/{})", startTaskId);
	}

	private String startProfile(Task startTask)
	{
		return startTask.getMeta().getProfile().stream().map(CanonicalType::getValue)
				.filter(v -> v != null && v.startsWith(ConstantsAtmp.PROFILE_TASK_ATMP_DATA_TRANSFER_START)).findFirst()
				.orElse(ConstantsAtmp.PROFILE_TASK_ATMP_DATA_TRANSFER_START);
	}

	/**
	 * @return {@code true} if any other in-progress start Task takes precedence over the current one, i.e. was authored
	 *         earlier, or at the same instant with a lexicographically smaller id
	 */
	boolean isDuplicateStart(Task startTask, List<Task> otherInProgress)
	{
		return otherInProgress.stream().anyMatch(other -> takesPrecedence(other, startTask));
	}

	private boolean takesPrecedence(Task other, Task current)
	{
		Date otherAuthored = other.getAuthoredOn(), currentAuthored = current.getAuthoredOn();

		// authoredOn is mandatory per dsf-task-base; a Task without it cannot be ordered, treat it as older so the
		// current start yields rather than risking two running loops
		if (otherAuthored == null)
			return true;
		if (currentAuthored == null)
			return false;

		int byAuthored = otherAuthored.compareTo(currentAuthored);
		if (byAuthored != 0)
			return byAuthored < 0;

		String otherId = other.getIdElement().getIdPart(), currentId = current.getIdElement().getIdPart();
		return otherId != null && currentId != null && otherId.compareTo(currentId) < 0;
	}
}
