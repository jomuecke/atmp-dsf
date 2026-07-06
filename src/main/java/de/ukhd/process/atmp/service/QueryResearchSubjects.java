package de.ukhd.process.atmp.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ResearchStudy;
import org.hl7.fhir.r4.model.ResearchSubject;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import de.ukhd.process.atmp.ConstantsAtmp;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.variables.Variables;

/**
 * Resolves the configured ATMP {@link ResearchStudy} in the local FHIR store and lists its {@link ResearchSubject}s.
 * Stores one entry per subject ({@code <patient-reference>|<pseudonym>}) for the following multi-instance subprocess.
 */
public class QueryResearchSubjects implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(QueryResearchSubjects.class);

	private final String fhirServerId;
	private final String studyIdentifierSystem;
	private final String studyIdentifierValue;

	public QueryResearchSubjects(String fhirServerId, String studyIdentifierSystem, String studyIdentifierValue)
	{
		this.fhirServerId = fhirServerId;
		this.studyIdentifierSystem = studyIdentifierSystem;
		this.studyIdentifierValue = studyIdentifierValue;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(fhirServerId, "fhirServerId");
		Objects.requireNonNull(studyIdentifierValue, "studyIdentifierValue");
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables) throws Exception
	{
		advanceCycleState(variables);

		IGenericClient client = api.getFhirClientProvider().getById(fhirServerId).orElseThrow(
				() -> new RuntimeException("FHIR client '" + fhirServerId + "' not configured in DSF BPE"));

		ResearchStudy study = findStudy(client);
		List<ResearchSubject> subjects = findSubjects(client, study);

		List<String> subjectEntries = subjects.stream().map(this::toSubjectEntry).filter(Objects::nonNull).toList();

		logger.info("Found {} ResearchSubject(s) for ATMP study '{}' in FHIR server '{}'", subjectEntries.size(),
				studyIdentifierValue, fhirServerId);

		variables.setStringList(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_RESEARCH_SUBJECTS, subjectEntries);
	}

	/**
	 * Runs once at the start of every cycle: promotes the previous cycle's start time to the watermark and consumes a
	 * one-shot force-bulk (both no-ops on the very first cycle, where no prior cycle start exists), then records this
	 * cycle's start time. Recording the start <em>before</em> querying ensures Observations updated during the cycle
	 * are re-picked next cycle rather than skipped.
	 */
	private void advanceCycleState(Variables variables)
	{
		String previousCycleStart = variables.getString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_START);
		if (previousCycleStart != null)
		{
			variables.setString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_WATERMARK, previousCycleStart);
			variables.setBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_FORCE_BULK, false);
		}

		variables.setString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_START, Instant.now().toString());
	}

	private ResearchStudy findStudy(IGenericClient client)
	{
		String identifier = (studyIdentifierSystem == null || studyIdentifierSystem.isBlank()) ? studyIdentifierValue
				: studyIdentifierSystem + "|" + studyIdentifierValue;

		Bundle result = client.search().forResource(ResearchStudy.class)
				.whereMap(java.util.Map.of("identifier", List.of(identifier))).returnBundle(Bundle.class).execute();

		return allResources(client, result).stream().filter(r -> r instanceof ResearchStudy).map(r -> (ResearchStudy) r)
				.findFirst().orElseThrow(() -> new RuntimeException("No ResearchStudy with identifier '" + identifier
						+ "' found in FHIR server '" + fhirServerId + "'"));
	}

	private List<ResearchSubject> findSubjects(IGenericClient client, ResearchStudy study)
	{
		String studyReference = study.getIdElement().toUnqualifiedVersionless().getValue();

		Bundle result = client.search().forResource(ResearchSubject.class)
				.whereMap(java.util.Map.of("study", List.of(studyReference))).returnBundle(Bundle.class).execute();

		return allResources(client, result).stream().filter(r -> r instanceof ResearchSubject)
				.map(r -> (ResearchSubject) r).toList();
	}

	private List<Resource> allResources(IGenericClient client, Bundle firstPage)
	{
		List<Resource> resources = new ArrayList<>();

		Bundle page = firstPage;
		while (page != null)
		{
			page.getEntry().stream().filter(Bundle.BundleEntryComponent::hasResource)
					.map(Bundle.BundleEntryComponent::getResource).forEach(resources::add);

			page = page.getLink(Bundle.LINK_NEXT) != null ? client.loadPage().next(page).execute() : null;
		}

		return resources;
	}

	private String toSubjectEntry(ResearchSubject subject)
	{
		String subjectId = subject.getIdElement().toUnqualifiedVersionless().getValue();

		if (!subject.hasIndividual() || !subject.getIndividual().hasReference())
		{
			logger.warn("Skipping ResearchSubject '{}': no Patient reference", subjectId);
			return null;
		}

		if (!subject.hasIdentifier() || !subject.getIdentifierFirstRep().hasValue())
		{
			logger.warn("Skipping ResearchSubject '{}': no pseudonym identifier", subjectId);
			return null;
		}

		return subject.getIndividual().getReference() + ConstantsAtmp.RESEARCH_SUBJECT_ENTRY_SEPARATOR
				+ subject.getIdentifierFirstRep().getValue();
	}
}
