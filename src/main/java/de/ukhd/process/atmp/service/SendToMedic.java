package de.ukhd.process.atmp.service;

import java.time.Instant;
import java.util.Objects;

import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.ukhd.process.atmp.ConstantsAtmp;
import de.ukhd.process.atmp.audit.AuditLog;
import de.ukhd.process.atmp.client.MedicClient;
import de.ukhd.process.atmp.variables.SeenSubjects;
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
		SubjectEntry subject = SubjectEntry.parse(variables.getString(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT));
		String pseudonym = subject.pseudonym();

		// Whole-cycle failure detected earlier in this cycle (Issue D): unmark without further audit noise so the
		// subject is retried in full next cycle; the abort itself was audited once when it was detected
		if (Boolean.TRUE.equals(variables.getBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED)))
		{
			unmarkSeen(variables, pseudonym);
			return;
		}

		// Creating this subject's bundle already failed and was audited (Issue D): skip sending and do not advance
		// state
		// (leave it unseen), so the subject is retried in full next cycle. Continue with the next subject.
		if (Boolean.TRUE.equals(variables.getBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT_ERROR)))
		{
			unmarkSeen(variables, pseudonym);
			return;
		}

		try
		{
			Bundle bundle = variables.getFhirResource(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT_BUNDLE);

			if (!bundle.hasEntry())
				logger.info("No matching Observations for current subject, nothing sent to MEDIC");
			else
			{
				String bundleJson = api.getFhirContext().newJsonParser().encodeResourceToString(bundle);
				medicClient.send(bundleJson);
			}

			// Advance state only after the subject has been handled without error (an empty bundle is a valid no-op
			// handling): the subject is now handled in this instance, so subsequent cycles query it incrementally
			// (bulk-on-first-sight).
			markSeen(variables, pseudonym);
		}
		catch (MedicClient.MedicUnreachableException exception)
		{
			// Whole-cycle failure (Issue D): MEDIC is down for everyone, not just this subject. Audit once, mark the
			// cycle aborted so the remaining subjects are skipped quietly, and keep the watermark from advancing next
			// cycle. The loop itself survives and retries on the next interval.
			logger.warn("MEDIC unreachable, aborting remaining cycle: {}", exception.getMessage(), exception);
			AuditLog.appendError(api, variables, AuditLog.cycleError(exception, Instant.now()));
			variables.setBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED, true);
			unmarkSeen(variables, pseudonym);
		}
		catch (Exception exception)
		{
			// Per-subject isolation (Issue D): a failed send must not abort the cycle. Record an auditable error on the
			// start Task and unmark the subject so its state is not advanced and it is retried in full next cycle.
			logger.warn("Failed sending bundle for subject with pseudonym '{}' to MEDIC: {}", pseudonym,
					exception.getMessage(), exception);
			AuditLog.appendError(api, variables,
					AuditLog.subjectError(pseudonym, subject.patientReference(), exception, Instant.now()));
			unmarkSeen(variables, pseudonym);
		}
	}

	private void markSeen(Variables variables, String pseudonym)
	{
		variables.setStringList(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, SeenSubjects
				.mark(variables.getStringList(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS), pseudonym));
	}

	private void unmarkSeen(Variables variables, String pseudonym)
	{
		variables.setStringList(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, SeenSubjects
				.unmark(variables.getStringList(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS), pseudonym));
	}
}
