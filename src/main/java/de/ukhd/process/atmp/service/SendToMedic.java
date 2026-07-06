package de.ukhd.process.atmp.service;

import java.util.Objects;

import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.ukhd.process.atmp.ConstantsAtmp;
import de.ukhd.process.atmp.client.MedicClient;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.variables.Variables;

/**
 * POSTs the current subject's pseudonymized collection {@link Bundle} to the MEDIC REST API. Bundles without entries
 * are not sent.
 */
public class SendToMedic implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(SendToMedic.class);

	private final MedicClient medicClient;

	public SendToMedic(MedicClient medicClient)
	{
		this.medicClient = medicClient;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(medicClient, "medicClient");
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables) throws Exception
	{
		Bundle bundle = variables.getFhirResource(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT_BUNDLE);

		if (!bundle.hasEntry())
		{
			logger.info("No matching Observations for current subject, nothing sent to MEDIC");
			return;
		}

		String bundleJson = api.getFhirContext().newJsonParser().encodeResourceToString(bundle);
		medicClient.send(bundleJson);
	}
}
