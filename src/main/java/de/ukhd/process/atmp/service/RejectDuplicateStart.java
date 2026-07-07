package de.ukhd.process.atmp.service;

import java.util.Objects;

import org.operaton.bpm.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.ukhd.process.atmp.ConstantsAtmp;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.variables.Variables;

/**
 * Rejects duplicate starts of the long-lived ATMP transfer loop. The current process instance already exists when this
 * task runs, so exactly one active instance is valid; more than one means another start was submitted while the loop
 * was still running. Duplicate instances are marked for the BPMN gateway and end immediately, without entering the
 * timer loop.
 */
public class RejectDuplicateStart implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(RejectDuplicateStart.class);

	private final RuntimeService runtimeService;

	public RejectDuplicateStart(RuntimeService runtimeService)
	{
		this.runtimeService = runtimeService;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(runtimeService, "runtimeService");
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables) throws Exception
	{
		long activeInstances = runtimeService.createProcessInstanceQuery()
				.processDefinitionKey(ConstantsAtmp.PROCESS_NAME_FULL_ATMP_DATA_TRANSFER).active().count();

		boolean duplicateStart = isDuplicateStart(activeInstances);
		variables.setBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_DUPLICATE_START, duplicateStart);

		if (duplicateStart)
			logger.warn("Rejected duplicate ATMP data transfer start: {} active process instances", activeInstances);
		else
			logger.info("ATMP data transfer singleton guard passed: {} active process instance", activeInstances);
	}

	boolean isDuplicateStart(long activeInstances)
	{
		return activeInstances > 1;
	}
}
