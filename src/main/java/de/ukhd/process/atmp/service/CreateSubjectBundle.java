package de.ukhd.process.atmp.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import de.ukhd.process.atmp.ConstantsAtmp;
import de.ukhd.process.atmp.fhir.ObservationBundleFactory;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.variables.Variables;

/**
 * For the current multi-instance subject ({@code <patient-reference>|<pseudonym>}): queries the subject's laboratory
 * {@link Observation}s (configured LOINC codes, status {@code final}) from the local FHIR store and transforms them via
 * {@link ObservationBundleFactory} into the pseudonymized collection {@link Bundle} to send.
 */
public class CreateSubjectBundle implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(CreateSubjectBundle.class);

	private final String fhirServerId;
	private final List<String> loincCodes;
	private final ObservationBundleFactory observationBundleFactory;

	public CreateSubjectBundle(String fhirServerId, List<String> loincCodes,
			ObservationBundleFactory observationBundleFactory)
	{
		this.fhirServerId = fhirServerId;
		this.loincCodes = loincCodes;
		this.observationBundleFactory = observationBundleFactory;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(fhirServerId, "fhirServerId");
		Objects.requireNonNull(loincCodes, "loincCodes");
		Objects.requireNonNull(observationBundleFactory, "observationBundleFactory");
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables) throws Exception
	{
		String subjectEntry = variables.getString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT);
		int separator = subjectEntry.lastIndexOf(ConstantsAtmp.RESEARCH_SUBJECT_ENTRY_SEPARATOR);
		String patientReference = subjectEntry.substring(0, separator);
		String pseudonym = subjectEntry.substring(separator + 1);

		IGenericClient client = api.getFhirClientProvider().getById(fhirServerId).orElseThrow(
				() -> new RuntimeException("FHIR client '" + fhirServerId + "' not configured in DSF BPE"));

		List<Observation> observations = findObservations(client, patientReference);
		Bundle bundle = observationBundleFactory.createFrom(observations, pseudonym);

		logger.info("Created collection bundle with {} Observation(s) for subject with pseudonym '{}'",
				bundle.getEntry().size(), pseudonym);

		variables.setFhirResource(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT_BUNDLE, bundle);
	}

	private List<Observation> findObservations(IGenericClient client, String patientReference)
	{
		List<String> codeTokens = loincCodes.stream().map(code -> ObservationBundleFactory.LOINC_SYSTEM + "|" + code)
				.toList();

		Bundle page = client
				.search().forResource(Observation.class).whereMap(Map.of("patient", List.of(patientReference), "status",
						List.of("final"), "code", List.of(String.join(",", codeTokens))))
				.returnBundle(Bundle.class).execute();

		List<Observation> observations = new ArrayList<>();
		while (page != null)
		{
			page.getEntry().stream().filter(Bundle.BundleEntryComponent::hasResource)
					.map(Bundle.BundleEntryComponent::getResource).filter(r -> r instanceof Observation)
					.map(r -> (Observation) r).forEach(observations::add);

			page = page.getLink(Bundle.LINK_NEXT) != null ? client.loadPage().next(page).execute() : null;
		}

		return observations;
	}
}
