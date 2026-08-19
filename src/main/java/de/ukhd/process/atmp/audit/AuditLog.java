package de.ukhd.process.atmp.audit;

import java.time.Instant;
import java.util.Objects;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;

import de.ukhd.process.atmp.ConstantsAtmp;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.variables.Variables;

/**
 * Builds and persists per-cycle audit entries for the ATMP data transfer (Issue&nbsp;D). Failures are appended as
 * {@code error}-coded {@link Task} outputs on the long-lived start Task so an operator can audit which subject/cycle
 * failed, why and when.
 *
 * <p>
 * Messages are deliberately built from the ATMP pseudonym, the exception type/message and a timestamp only. The local
 * patient identity is redacted (see {@link #subjectError(String, String, Throwable, Instant)}) and the register API key
 * never appears in any exception (it is sent as a request header, never echoed).
 */
public final class AuditLog
{
	/**
	 * Maximum number of {@code error} outputs kept on the start Task. The Task lives as long as the loop; without a
	 * cap, a persistent failure on an hourly timer would grow it without bound. When full, the oldest entries are
	 * dropped in favor of the newest.
	 */
	public static final int MAX_ERROR_OUTPUTS = 20;

	private AuditLog()
	{
	}

	/**
	 * Audit line for a single subject's failure:
	 * {@code "[<timestamp>] subject <pseudonym>: <ExceptionClass>: <message>"}. Any occurrence of the local
	 * {@code patientReference} in the exception message (e.g. a FHIR search URL) is redacted to
	 * {@code Patient/<pseudonym>} so the local patient identity does not leak into the auditable output.
	 *
	 * @param pseudonym
	 *            the subject's ATMP pseudonym, not <code>null</code>
	 * @param patientReference
	 *            the local patient reference to redact, or <code>null</code> for none
	 * @param cause
	 *            the failure, not <code>null</code>
	 * @param timestamp
	 *            when the failure occurred, not <code>null</code>
	 */
	public static String subjectError(String pseudonym, String patientReference, Throwable cause, Instant timestamp)
	{
		Objects.requireNonNull(cause, "cause");

		return subjectProblem(pseudonym, patientReference, cause.getClass().getSimpleName() + ": " + safeMessage(cause),
				timestamp);
	}

	/**
	 * Audit line for a subject-level problem that is not an exception — the register acknowledged the request but did
	 * not accept all of it, or Observations were left out of the bundle because the register's schema would reject
	 * them: {@code "[<timestamp>] subject <pseudonym>: <detail>"}. {@code detail} is redacted like
	 * {@link #subjectError(String, String, Throwable, Instant)}.
	 *
	 * @param pseudonym
	 *            the subject's ATMP pseudonym, not <code>null</code>
	 * @param patientReference
	 *            the local patient reference to redact, or <code>null</code> for none
	 * @param detail
	 *            what went wrong, not <code>null</code>
	 * @param timestamp
	 *            when it happened, not <code>null</code>
	 */
	public static String subjectProblem(String pseudonym, String patientReference, String detail, Instant timestamp)
	{
		Objects.requireNonNull(pseudonym, "pseudonym");
		Objects.requireNonNull(detail, "detail");
		Objects.requireNonNull(timestamp, "timestamp");

		return "[" + timestamp + "] subject " + pseudonym + ": " + redact(detail, patientReference, pseudonym);
	}

	/**
	 * Audit line for a whole-cycle failure (FHIR store / register unreachable):
	 * {@code "[<timestamp>] cycle skipped: <ExceptionClass>: <message>"}. Contains configuration/URL detail only, never
	 * a patient identity.
	 */
	public static String cycleError(Throwable cause, Instant timestamp)
	{
		Objects.requireNonNull(cause, "cause");
		Objects.requireNonNull(timestamp, "timestamp");

		return "[" + timestamp + "] cycle skipped: " + cause.getClass().getSimpleName() + ": " + safeMessage(cause);
	}

	/**
	 * Appends {@code message} as an {@code error}-coded output on the process' start Task and persists the change,
	 * keeping at most {@link #MAX_ERROR_OUTPUTS} error outputs (oldest dropped first).
	 */
	public static void appendError(ProcessPluginApi api, Variables variables, String message)
	{
		Objects.requireNonNull(api, "api");
		Objects.requireNonNull(variables, "variables");
		Objects.requireNonNull(message, "message");

		Task startTask = variables.getStartTask();
		appendErrorOutput(startTask, message);

		// Variables.updateTask only refreshes Camunda's serialized Task variable; it does not persist to the DSF FHIR
		// server. Persist first and keep the returned resource version in the process variable so the next audit append
		// does not overwrite from a stale Task version.
		Task updated = api.getDsfClientProvider().getLocal().update(startTask);
		variables.updateTask(updated);
	}

	/**
	 * Appends {@code message} as an {@code error}-coded output on {@code task}, dropping the oldest error outputs so at
	 * most {@link #MAX_ERROR_OUTPUTS} remain. Outputs with other codes are never touched.
	 */
	static void appendErrorOutput(Task task, String message)
	{
		task.addOutput(
				new Task.TaskOutputComponent(
						new CodeableConcept().addCoding(new Coding(ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER,
								ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER_VALUE_ERROR, null)),
						new StringType(message)));

		long errorCount = task.getOutput().stream().filter(AuditLog::isErrorOutput).count();
		long toDrop = errorCount - MAX_ERROR_OUTPUTS;
		if (toDrop > 0)
		{
			var iterator = task.getOutput().iterator();
			while (toDrop > 0 && iterator.hasNext())
			{
				if (isErrorOutput(iterator.next()))
				{
					iterator.remove();
					toDrop--;
				}
			}
		}
	}

	private static boolean isErrorOutput(Task.TaskOutputComponent output)
	{
		return output.getType().getCoding().stream()
				.anyMatch(c -> ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER.equals(c.getSystem())
						&& ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER_VALUE_ERROR.equals(c.getCode()));
	}

	private static String safeMessage(Throwable cause)
	{
		return cause.getMessage() == null ? "" : cause.getMessage();
	}

	private static String redact(String message, String patientReference, String pseudonym)
	{
		if (patientReference == null || patientReference.isBlank())
			return message;

		// Redact both the full reference (e.g. a "patient=Patient/<id>" search URL) and the bare logical id, which can
		// surface on its own or inside a URL-encoded reference. Biased towards over-redaction: pseudonymization safety
		// beats a slightly noisier audit message.
		String redacted = message.replace(patientReference, "Patient/" + pseudonym);

		int lastSlash = patientReference.lastIndexOf('/');
		String bareId = lastSlash >= 0 ? patientReference.substring(lastSlash + 1) : patientReference;
		if (!bareId.isBlank())
			redacted = redacted.replace(bareId, pseudonym);

		return redacted;
	}
}
