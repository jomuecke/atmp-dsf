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
import de.ukhd.process.atmp.audit.AuditLog;
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
		// Capture the cycle start before querying so Observations updated during the cycle are re-picked next cycle
		// rather than skipped; it is only committed to the state once the query below succeeds.
		Instant cycleStart = Instant.now();

		List<String> subjectEntries;
		try
		{
			IGenericClient client = api.getFhirClientProvider().getById(fhirServerId).orElseThrow(
					() -> new RuntimeException("FHIR client '" + fhirServerId + "' not configured in DSF BPE"));

			ResearchStudy study = findStudy(client);
			List<ResearchSubject> subjects = findSubjects(client, study);

			subjectEntries = subjects.stream().map(this::toSubjectEntry).filter(Objects::nonNull).toList();
		}
		catch (Exception exception)
		{
			// Whole-cycle isolation (Issue D): FHIR store / register down must not terminate the long-lived instance.
			// Record
			// an auditable error, run zero subject instances (empty list) so the loop falls straight through to the
			// timer,
			// and leave the watermark unadvanced so the tick is retried on the next interval.
			logger.warn("ATMP cycle skipped: could not query research subjects from FHIR server '{}': {}", fhirServerId,
					exception.getMessage(), exception);
			AuditLog.appendError(api, variables, AuditLog.cycleError(exception, Instant.now()));
			variables.setStringList(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_RESEARCH_SUBJECTS, List.of());
			return;
		}

		advanceCycleState(variables, cycleStart);

		logger.info("Found {} ResearchSubject(s) for ATMP study '{}' in FHIR server '{}'", subjectEntries.size(),
				studyIdentifierValue, fhirServerId);

		variables.setStringList(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_RESEARCH_SUBJECTS, subjectEntries);
	}

	/**
	 * Runs after a successful query at the start of every cycle: promotes the previous cycle's start time to the
	 * watermark (a no-op on the very first cycle, where no prior cycle start exists), then records {@code cycleStart}
	 * (captured before the query). A skipped cycle does not call this, and after an aborted cycle (register unreachable
	 * mid-cycle) the promotion is withheld, so the watermark stays put and the tick is retried next interval.
	 * <p>
	 * Promoting the <i>previous</i> cycle's start (instead of the completed cycle's own start) deliberately re-queries
	 * one full interval of overlap each cycle: it is equivalent to advancing the watermark "after all subjects" while
	 * keeping the advance in a single place, and re-sends are absorbed by the register's upsert-by-id.
	 */
	private void advanceCycleState(Variables variables, Instant cycleStart)
	{
		boolean previousCycleAborted = Boolean.TRUE
				.equals(variables.getBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED));
		String previousCycleStart = variables.getString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_START);

		if (previousCycleStart != null && !previousCycleAborted)
			variables.setString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_WATERMARK, previousCycleStart);

		variables.setBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED, false);
		variables.setString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_START, cycleStart.toString());
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
