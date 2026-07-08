package de.ukhd.process.atmp.service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.ukhd.process.atmp.ConstantsAtmp;
import de.ukhd.process.atmp.audit.AuditLog;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.variables.Variables;

/**
 * Runs once at process start: resolves the cycle timer interval (Task {@code timer-interval} input, else configured
 * default) into the {@code atmpTimerInterval} process variable read by the BPMN cycle timer, and seeds the incremental
 * state — an empty seen-subjects set and a cleared cycle-aborted flag. Because it runs before the timer loop, these are
 * seeded exactly once and then persist across BPE restarts with the rest of the process variables. A fresh instance
 * always starts with a full (bulk) first cycle, so a full re-send is forced by stop + start.
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
		Duration.parse(defaultTimerInterval);
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables) throws Exception
	{
		String timerInterval = api.getTaskHelper()
				.getFirstInputParameterStringValue(variables.getStartTask(),
						ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER,
						ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER_VALUE_TIMER_INTERVAL)
				.map(String::trim).filter(s -> !s.isEmpty()).orElse(defaultTimerInterval);

		// The profile regex should have rejected unparseable values, but a mismatch between the regex and
		// Duration.parse must not incident the loop before it ever ran: fall back to the default and record the
		// correction as an auditable error on the start Task
		if (!isParseable(timerInterval))
		{
			logger.warn("Invalid timer-interval '{}' in start Task, falling back to default '{}'", timerInterval,
					defaultTimerInterval);
			AuditLog.appendError(api, variables, "[" + Instant.now() + "] invalid timer-interval '" + timerInterval
					+ "', using default '" + defaultTimerInterval + "'");
			timerInterval = defaultTimerInterval;
		}

		variables.setString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_TIMER_INTERVAL, timerInterval);
		variables.setStringList(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, List.of());
		variables.setBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED, false);

		logger.info("ATMP data transfer timer interval set to '{}'", timerInterval);
	}

	private boolean isParseable(String timerInterval)
	{
		try
		{
			Duration.parse(timerInterval);
			return true;
		}
		catch (DateTimeParseException exception)
		{
			return false;
		}
	}
}
