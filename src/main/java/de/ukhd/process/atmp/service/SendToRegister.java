package de.ukhd.process.atmp.service;

import java.time.Instant;
import java.util.Objects;

import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.ukhd.process.atmp.ConstantsAtmp;
import de.ukhd.process.atmp.audit.AuditLog;
import de.ukhd.process.atmp.client.RegisterAck;
import de.ukhd.process.atmp.client.RegisterClient;
import de.ukhd.process.atmp.variables.SeenSubjects;
import de.ukhd.process.atmp.variables.SubjectEntry;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.activity.ServiceTask;
import dev.dsf.bpe.v2.variables.Variables;

/**
 * POSTs the current subject's pseudonymized collection {@link Bundle} to the register REST API. Bundles without entries
 * are not sent.
 */
public class SendToRegister implements ServiceTask, InitializingBean
{
	private static final Logger logger = LoggerFactory.getLogger(SendToRegister.class);

	private final RegisterClient registerClient;

	public SendToRegister(RegisterClient registerClient)
	{
		this.registerClient = registerClient;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(registerClient, "registerClient");
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
			{
				logger.info("No matching Observations for current subject, nothing sent to register");
				markSeen(variables, pseudonym);
				return;
			}

			String bundleJson = api.getFhirContext().newJsonParser().encodeResourceToString(bundle);
			RegisterAck ack = registerClient.send(bundleJson);

			if (!ack.isFullyAccepted())
			{
				// The register answered 2xx but did not store everything: an unknown PID (skippable), a hard failure,
				// or an entry its schema rejected. Treating this as success would hide data loss behind a green
				// status, so the subject stays unseen and is re-sent in full next cycle — for an unknown PID that
				// means it is backfilled automatically once the pseudonym is provisioned in the register.
				logger.warn("Register did not accept everything for subject with pseudonym '{}': {}", pseudonym,
						ack.describeProblems());
				AuditLog.appendError(api, variables, AuditLog.subjectProblem(pseudonym, subject.patientReference(),
						"not fully accepted by the register, will be re-sent next cycle: " + ack.describeProblems(),
						Instant.now()));
				unmarkSeen(variables, pseudonym);
				return;
			}

			// Advance state only after the subject has been handled without error (an empty bundle is a valid no-op
			// handling): the subject is now handled in this instance, so subsequent cycles query it incrementally
			// (bulk-on-first-sight).
			markSeen(variables, pseudonym);
		}
		catch (RegisterClient.RegisterCycleException exception)
		{
			// Whole-cycle failure (Issue D): the register is unreachable, unhealthy, or rejecting our API key — it
			// fails identically for every subject, not just this one. Audit once, mark the cycle aborted so the
			// remaining subjects are skipped quietly, and keep the watermark from advancing next cycle. The loop
			// itself survives and retries on the next interval.
			logger.warn("Register not usable for this cycle, aborting the remaining subjects: {}",
					exception.getMessage(), exception);
			AuditLog.appendError(api, variables, AuditLog.cycleError(exception, Instant.now()));
			variables.setBoolean(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED, true);
			unmarkSeen(variables, pseudonym);
		}
		catch (Exception exception)
		{
			auditSubjectFailure(api, variables, subject, exception);
		}
	}

	/**
	 * Per-subject isolation (Issue D): a failed send must not abort the cycle. Records an auditable error on the start
	 * Task and unmarks the subject so its state is not advanced and it is retried in full next cycle.
	 */
	private void auditSubjectFailure(ProcessPluginApi api, Variables variables, SubjectEntry subject,
			Exception exception)
	{
		logger.warn("Failed sending bundle for subject with pseudonym '{}' to register: {}", subject.pseudonym(),
				exception.getMessage(), exception);
		AuditLog.appendError(api, variables,
				AuditLog.subjectError(subject.pseudonym(), subject.patientReference(), exception, Instant.now()));
		unmarkSeen(variables, subject.pseudonym());
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
