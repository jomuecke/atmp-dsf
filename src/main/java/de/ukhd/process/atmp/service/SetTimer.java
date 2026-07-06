package de.ukhd.process.atmp.service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.hl7.fhir.r4.model.BooleanType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.ukhd.process.atmp.ConstantsAtmp;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.variables.Variables;

/**
 * Runs once at process start: resolves the cycle timer interval (Task {@code timer-interval} input, else configured
 * default) into the {@code atmpTimerInterval} process variable read by the BPMN cycle timer, and seeds the incremental
 * state — the one-shot {@code force-bulk} flag (from the Task input) and an empty seen-subjects set. Because it runs
 * before the timer loop, these are seeded exactly once and then persist across BPE restarts with the rest of the
 * process variables.
 */
public class SetTimer implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(SetTimer.class);

	private final String defaultTimerInterval;

	public SetTimer(String defaultTimerInterval)
	{
		this.defaultTimerInterval = defaultTimerInterval;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(defaultTimerInterval, "defaultTimerInterval");
		parseDuration(defaultTimerInterval);
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables) throws Exception
	{
		String timerInterval = api.getTaskHelper()
				.getFirstInputParameterStringValue(variables.getStartTask(),
						ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER,
						ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER_VALUE_TIMER_INTERVAL)
				.map(String::trim).filter(s -> !s.isEmpty()).orElse(defaultTimerInterval);

		parseDuration(timerInterval);
		variables.setString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_TIMER_INTERVAL, timerInterval);

		boolean forceBulk = readForceBulk(api, variables);
		variables.setBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_FORCE_BULK, forceBulk);
		variables.setStringList(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, List.of());

		logger.info("ATMP data transfer timer interval set to '{}', force-bulk={}", timerInterval, forceBulk);
	}

	private boolean readForceBulk(ProcessPluginApi api, Variables variables)
	{
		return api.getTaskHelper()
				.getFirstInputParameterValue(variables.getStartTask(), ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER,
						ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER_VALUE_FORCE_BULK, BooleanType.class)
				.map(BooleanType::booleanValue).orElse(false);
	}

	private void parseDuration(String timerInterval)
	{
		Duration.parse(timerInterval);
	}
}
