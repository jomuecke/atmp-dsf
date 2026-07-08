package de.ukhd.process.atmp.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.junit.Before;
import org.junit.Test;

import ca.uhn.fhir.context.FhirContext;
import de.ukhd.process.atmp.ConstantsAtmp;
import de.ukhd.process.atmp.client.MedicClient;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.variables.Variables;

/**
 * Behaviour test for the Issue D error wiring of {@link SendToMedic}: per-subject failures are audited on the start
 * Task and do not advance the subject's seen-state, a MEDIC-unreachable failure aborts the remaining cycle with a
 * single audit entry.
 */
public class SendToMedicTest
{
	private static final String PSEUDONYM = "ATMP-0001";
	private static final String SUBJECT_ENTRY = "Patient/local-patient-1"
			+ ConstantsAtmp.RESEARCH_SUBJECT_ENTRY_SEPARATOR + PSEUDONYM;

	private static class FailingMedicClient extends MedicClient
	{
		private final RuntimeException failure;
		private int sendCount;

		FailingMedicClient(RuntimeException failure)
		{
			super("http://medic.test", "unused");
			this.failure = failure;
		}

		@Override
		public void send(String bundleJson)
		{
			sendCount++;
			if (failure != null)
				throw failure;
		}
	}

	private final Map<String, Object> store = new HashMap<>();
	private final Task startTask = new Task();

	@Before
	public void setUp()
	{
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT, SUBJECT_ENTRY);
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, new ArrayList<>(List.of()));

		// the Observation needs content: HAPI's Bundle.hasEntry() treats an entry holding an empty resource as empty
		Observation observation = new Observation();
		observation.setStatus(Observation.ObservationStatus.FINAL);

		Bundle bundle = new Bundle();
		bundle.addEntry().setResource(observation);
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT_BUNDLE, bundle);
	}

	@Test
	public void testSuccessfulSendMarksSubjectSeen() throws Exception
	{
		FailingMedicClient client = new FailingMedicClient(null);

		new SendToMedic(client).execute(api(), variables());

		assertEquals(1, client.sendCount);
		assertTrue(seenSubjects().contains(PSEUDONYM));
		assertTrue(startTask.getOutput().isEmpty());
	}

	@Test
	public void testSendFailureAuditsErrorAndLeavesSubjectUnseen() throws Exception
	{
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, new ArrayList<>(List.of(PSEUDONYM)));

		new SendToMedic(new FailingMedicClient(new RuntimeException("MEDIC returned status 422"))).execute(api(),
				variables());

		assertFalse("failed subject must be retried in full next cycle", seenSubjects().contains(PSEUDONYM));
		assertEquals(1, startTask.getOutput().size());
		String audit = ((StringType) startTask.getOutputFirstRep().getValue()).getValue();
		assertTrue(audit.contains(PSEUDONYM));
		assertTrue(audit.contains("MEDIC returned status 422"));
		assertFalse("a per-subject failure must not abort the cycle",
				Boolean.TRUE.equals((Boolean) store.get(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED)));
	}

	@Test
	public void testMedicUnreachableAbortsCycleWithSingleAudit() throws Exception
	{
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, new ArrayList<>(List.of(PSEUDONYM)));

		new SendToMedic(new FailingMedicClient(
				new MedicClient.MedicUnreachableException("Could not reach MEDIC API", new RuntimeException())))
				.execute(api(), variables());

		assertTrue(Boolean.TRUE.equals((Boolean) store.get(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED)));
		assertFalse(seenSubjects().contains(PSEUDONYM));
		assertEquals(1, startTask.getOutput().size());
		assertTrue(((StringType) startTask.getOutputFirstRep().getValue()).getValue().contains("cycle skipped"));
	}

	@Test
	public void testAbortedCycleSkipsSendingAndAuditing() throws Exception
	{
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED, Boolean.TRUE);
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, new ArrayList<>(List.of(PSEUDONYM)));
		FailingMedicClient client = new FailingMedicClient(null);

		new SendToMedic(client).execute(api(), variables());

		assertEquals(0, client.sendCount);
		assertFalse(seenSubjects().contains(PSEUDONYM));
		assertTrue("abort is audited once when detected, not per remaining subject", startTask.getOutput().isEmpty());
	}

	@Test
	public void testSubjectErrorFromCreateSkipsSendingWithoutSecondAudit() throws Exception
	{
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT_ERROR, Boolean.TRUE);
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, new ArrayList<>(List.of(PSEUDONYM)));
		FailingMedicClient client = new FailingMedicClient(null);

		new SendToMedic(client).execute(api(), variables());

		assertEquals(0, client.sendCount);
		assertFalse(seenSubjects().contains(PSEUDONYM));
		assertTrue("bundle-creation failure was already audited by CreateSubjectBundle",
				startTask.getOutput().isEmpty());
	}

	@SuppressWarnings("unchecked")
	private List<String> seenSubjects()
	{
		return (List<String>) store.get(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS);
	}

	private Variables variables()
	{
		return (Variables) Proxy.newProxyInstance(Variables.class.getClassLoader(), new Class<?>[] { Variables.class },
				(proxy, method, args) -> switch (method.getName())
				{
					case "getString", "getBoolean", "getStringList", "getFhirResource" -> store.get(args[0]);
					case "setString", "setBoolean", "setStringList", "setFhirResource" ->
						store.put((String) args[0], args[1]);
					case "getStartTask" -> startTask;
					case "updateTask" -> null;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private ProcessPluginApi api()
	{
		return (ProcessPluginApi) Proxy.newProxyInstance(ProcessPluginApi.class.getClassLoader(),
				new Class<?>[] { ProcessPluginApi.class }, (proxy, method, args) -> switch (method.getName())
				{
					case "getFhirContext" -> FhirContext.forR4Cached();
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
