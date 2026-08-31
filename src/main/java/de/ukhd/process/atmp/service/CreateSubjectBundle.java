package de.ukhd.process.atmp.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import de.ukhd.process.atmp.ConstantsAtmp;
import de.ukhd.process.atmp.audit.AuditLog;
import de.ukhd.process.atmp.fhir.ObservationBundleFactory;
import de.ukhd.process.atmp.fhir.ObservationBundleFactory.RejectedObservation;
import de.ukhd.process.atmp.fhir.ObservationBundleFactory.SubjectBundle;
import de.ukhd.process.atmp.variables.SubjectEntry;
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

	/** Cap on how many rejected Observations are named in a single audit entry, so it stays readable. */
	private static final int MAX_AUDITED_REJECTIONS = 5;

	private final String fhirServerId;
	private final List<String> loincCodes;
	private final ObservationBundleFactory observationBundleFactory;

	public CreateSubjectBundle(String fhirServerId, List<String> loincCodes,
			ObservationBundleFactory observationBundleFactory)
	{
		this.fhirServerId = fhirServerId;
		this.loincCodes = List.copyOf(Objects.requireNonNull(loincCodes, "loincCodes"));
		this.observationBundleFactory = observationBundleFactory;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(fhirServerId, "fhirServerId");
		Objects.requireNonNull(observationBundleFactory, "observationBundleFactory");
	}

	@Override
	public void execute(ProcessPluginApi api, Variables variables) throws Exception
	{
		SubjectEntry subject = SubjectEntry.parse(variables.getString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT));
		String pseudonym = subject.pseudonym();

		// Reset the per-subject error flag for this multi-instance iteration (process variables persist across
		// instances)
		variables.setBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT_ERROR, false);

		// Whole-cycle failure detected earlier in this cycle (Issue D): skip the remaining subjects without further
		// per-subject work or audit noise; SendToRegister unmarks them so they are retried in full next cycle
		if (Boolean.TRUE.equals(variables.getBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED)))
			return;

		try
		{
			Optional<Instant> lowerBound = observationBundleFactory.queryLowerBound(pseudonym, watermark(variables),
					seenSubjects(variables));

			IGenericClient client = api.getFhirClientProvider().getById(fhirServerId).orElseThrow(
					() -> new RuntimeException("FHIR client '" + fhirServerId + "' not configured in DSF BPE"));

			List<Observation> observations = findObservations(client, subject.patientReference(), lowerBound);
			SubjectBundle subjectBundle = observationBundleFactory.createFrom(observations, pseudonym);
			Bundle bundle = subjectBundle.bundle();

			logger.info("Created collection bundle with {} Observation(s) for subject with pseudonym '{}' ({} query)",
					bundle.getEntry().size(), pseudonym, lowerBound.isPresent() ? "incremental" : "full");

			auditRejectedObservations(api, variables, subject, subjectBundle.rejected());

			variables.setFhirResource(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT_BUNDLE, bundle);
		}
		catch (Exception exception)
		{
			// Per-subject isolation (Issue D): a bad subject must not abort the cycle. Record an auditable error on the
			// start Task, flag the subject as failed so SendToRegister skips it, and continue with the next subject.
			// State
			// is
			// not advanced (subject stays unseen), so it is retried in full next cycle.
			logger.warn("Failed creating bundle for subject with pseudonym '{}': {}", pseudonym, exception.getMessage(),
					exception);
			AuditLog.appendError(api, variables,
					AuditLog.subjectError(pseudonym, subject.patientReference(), exception, Instant.now()));
			variables.setBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT_ERROR, true);
		}
	}

	/**
	 * Records Observations that were selected for transfer but cannot satisfy the register's import schema (e.g. a
	 * local store that does not populate {@code meta.source}, or a date-only {@code effectiveDateTime}). They are left
	 * out of the bundle rather than making the whole subject fail, but must not disappear silently — the rest of the
	 * subject is still sent, so this is a warning, not a per-subject error.
	 */
	private void auditRejectedObservations(ProcessPluginApi api, Variables variables, SubjectEntry subject,
			List<RejectedObservation> rejected)
	{
		if (rejected.isEmpty())
			return;

		String detail = rejected.stream().limit(MAX_AUDITED_REJECTIONS).map(RejectedObservation::toString)
				.collect(Collectors.joining("; "));
		if (rejected.size() > MAX_AUDITED_REJECTIONS)
			detail += "; … and " + (rejected.size() - MAX_AUDITED_REJECTIONS) + " more";

		logger.warn("{} Observation(s) of subject with pseudonym '{}' not sent, rejected by the register's schema: {}",
				rejected.size(), subject.pseudonym(), detail);
		AuditLog.appendError(api, variables,
				AuditLog.subjectProblem(
						subject.pseudonym(), subject.patientReference(), rejected.size()
								+ " Observation(s) not sent, would be rejected by the register's schema: " + detail,
						Instant.now()));
	}

	private Instant watermark(Variables variables)
	{
		String value = variables.getString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_WATERMARK);
		return value == null ? null : Instant.parse(value);
	}

	private List<String> seenSubjects(Variables variables)
	{
		List<String> seen = variables.getStringList(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS);
		return seen == null ? List.of() : seen;
	}

	private List<Observation> findObservations(IGenericClient client, String patientReference,
			Optional<Instant> lowerBound)
	{
		List<String> codeTokens = loincCodes.stream().map(code -> ObservationBundleFactory.LOINC_SYSTEM + "|" + code)
				.toList();

		Map<String, List<String>> query = new HashMap<>();
		query.put("patient", List.of(patientReference));
		query.put("status", List.of("final"));
		query.put("code", List.of(String.join(",", codeTokens)));
		lowerBound.ifPresent(bound -> query.put("_lastUpdated", List.of("gt" + bound.toString())));

		Bundle page = client.search().forResource(Observation.class).whereMap(query).returnBundle(Bundle.class)
				.execute();

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
