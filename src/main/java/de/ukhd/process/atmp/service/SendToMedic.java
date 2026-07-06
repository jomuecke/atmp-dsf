package de.ukhd.process.atmp.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.ukhd.process.atmp.ConstantsAtmp;
import de.ukhd.process.atmp.client.MedicClient;
import de.ukhd.process.atmp.variables.SubjectEntry;
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
		String pseudonym = SubjectEntry.parse(variables.getString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT))
				.pseudonym();

		if (!bundle.hasEntry())
			logger.info("No matching Observations for current subject, nothing sent to MEDIC");
		else
		{
			String bundleJson = api.getFhirContext().newJsonParser().encodeResourceToString(bundle);
			medicClient.send(bundleJson);
		}

		// Advance state only after the subject has been handled without error (an exception above skips this; an empty
		// bundle is a valid no-op handling): the subject is now handled in this instance, so subsequent cycles query it
		// incrementally (bulk-on-first-sight).
		markSeen(variables, pseudonym);
	}

	private void markSeen(Variables variables, String pseudonym)
	{
		List<String> seen = new ArrayList<>(
				Optional.ofNullable(variables.getStringList(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS))
						.orElseGet(List::of));

		if (!seen.contains(pseudonym))
		{
			seen.add(pseudonym);
			variables.setStringList(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, seen);
		}
	}
}
