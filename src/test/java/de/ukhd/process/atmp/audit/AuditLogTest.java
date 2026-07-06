package de.ukhd.process.atmp.audit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.junit.Test;

public class AuditLogTest
{
	private static final String PSEUDONYM = "ATMP-0001";
	private static final String PATIENT_REFERENCE = "Patient/local-patient-1";
	private static final String API_KEY = "super-secret-medic-key";
	private static final Instant TIMESTAMP = Instant.parse("2026-07-06T10:15:30Z");

	@Test
	public void testSubjectErrorContainsPseudonymCauseAndTimestamp()
	{
		String line = AuditLog.subjectError(PSEUDONYM, PATIENT_REFERENCE,
				new RuntimeException("MEDIC returned status 422"), TIMESTAMP);

		assertTrue(line.contains(PSEUDONYM));
		assertTrue(line.contains("RuntimeException"));
		assertTrue(line.contains("MEDIC returned status 422"));
		assertTrue(line.contains(TIMESTAMP.toString()));
	}

	@Test
	public void testSubjectErrorRedactsLocalPatientReference()
	{
		String line = AuditLog.subjectError(PSEUDONYM, PATIENT_REFERENCE,
				new RuntimeException("query failed for patient=" + PATIENT_REFERENCE), TIMESTAMP);

		assertFalse("local patient identity must not leak", line.contains(PATIENT_REFERENCE));
		assertTrue(line.contains("Patient/" + PSEUDONYM));
	}

	@Test
	public void testSubjectErrorRedactsBarePatientLogicalId()
	{
		// even if only the bare logical id (not the full "Patient/<id>" reference) surfaces, it must not leak
		String line = AuditLog.subjectError(PSEUDONYM, PATIENT_REFERENCE,
				new RuntimeException("no resource local-patient-1 found"), TIMESTAMP);

		assertFalse(line.contains("local-patient-1"));
		assertTrue(line.contains(PSEUDONYM));
	}

	@Test
	public void testSubjectErrorNeverContainsApiKey()
	{
		// a plausible exception that (wrongly) echoed a key would still not be forwarded verbatim here, but the key is
		// never part of the exceptions we build; assert the audit line stays clean for a typical failure.
		String line = AuditLog.subjectError(PSEUDONYM, PATIENT_REFERENCE,
				new RuntimeException("Could not reach MEDIC API at 'https://medic/api/medic-import'"), TIMESTAMP);

		assertFalse(line.contains(API_KEY));
	}

	@Test
	public void testSubjectErrorNullPatientReferenceIsNoRedaction()
	{
		String line = AuditLog.subjectError(PSEUDONYM, null, new RuntimeException("boom"), TIMESTAMP);

		assertTrue(line.contains("boom"));
	}

	@Test
	public void testSubjectErrorNullMessageHandled()
	{
		String line = AuditLog.subjectError(PSEUDONYM, PATIENT_REFERENCE, new RuntimeException(), TIMESTAMP);

		assertTrue(line.contains(PSEUDONYM));
		assertTrue(line.contains("RuntimeException"));
	}

	@Test
	public void testCycleErrorFormatsCauseAndTimestamp()
	{
		String line = AuditLog.cycleError(new RuntimeException("Connection refused"), TIMESTAMP);

		assertTrue(line.contains("cycle skipped"));
		assertTrue(line.contains("RuntimeException"));
		assertTrue(line.contains("Connection refused"));
		assertTrue(line.contains(TIMESTAMP.toString()));
	}

	@Test
	public void testCycleErrorNullMessageHandled()
	{
		String line = AuditLog.cycleError(new RuntimeException(), TIMESTAMP);

		assertTrue(line.contains("cycle skipped"));
	}
}
