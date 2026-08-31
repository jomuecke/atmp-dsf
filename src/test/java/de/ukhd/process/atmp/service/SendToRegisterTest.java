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
import de.ukhd.process.atmp.client.RegisterAck;
import de.ukhd.process.atmp.client.RegisterClient;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.client.dsf.DsfClient;
import dev.dsf.bpe.v2.service.DsfClientProvider;
import dev.dsf.bpe.v2.variables.Variables;

/**
 * Behaviour test for the Issue D error wiring of {@link SendToRegister}: per-subject failures are audited on the start
 * Task and do not advance the subject's seen-state, a register-unreachable failure aborts the remaining cycle with a
 * single audit entry.
 */
public class SendToRegisterTest
{
	private static final String PSEUDONYM = "ATMP-0001";
	private static final String SUBJECT_ENTRY = "Patient/local-patient-1"
			+ ConstantsAtmp.RESEARCH_SUBJECT_ENTRY_SEPARATOR + PSEUDONYM;

	private static class FailingRegisterClient implements RegisterClient
	{
		private final RuntimeException failure;
		private final RegisterAck ack;
		private int sendCount;

		FailingRegisterClient(RuntimeException failure)
		{
			this(failure, RegisterAck.accepted());
		}

		FailingRegisterClient(RuntimeException failure, RegisterAck ack)
		{
			this.failure = failure;
			this.ack = ack;
		}

		@Override
		public RegisterAck send(String bundleJson)
		{
			sendCount++;
			if (failure != null)
				throw failure;

			return ack;
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
		FailingRegisterClient client = new FailingRegisterClient(null);

		new SendToRegister(client).execute(api(), variables());

		assertEquals(1, client.sendCount);
		assertTrue(seenSubjects().contains(PSEUDONYM));
		assertTrue(startTask.getOutput().isEmpty());
	}

	@Test
	public void testSendFailureAuditsErrorAndLeavesSubjectUnseen() throws Exception
	{
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, new ArrayList<>(List.of(PSEUDONYM)));

		new SendToRegister(new FailingRegisterClient(new RuntimeException("Register returned status 422")))
				.execute(api(), variables());

		assertFalse("failed subject must be retried in full next cycle", seenSubjects().contains(PSEUDONYM));
		assertEquals(1, startTask.getOutput().size());
		String audit = ((StringType) startTask.getOutputFirstRep().getValue()).getValue();
		assertTrue(audit.contains(PSEUDONYM));
		assertTrue(audit.contains("Register returned status 422"));
		assertFalse("a per-subject failure must not abort the cycle",
				Boolean.TRUE.equals((Boolean) store.get(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED)));
	}

	@Test
	public void testRegisterUnreachableAbortsCycleWithSingleAudit() throws Exception
	{
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, new ArrayList<>(List.of(PSEUDONYM)));

		new SendToRegister(new FailingRegisterClient(
				new RegisterClient.RegisterCycleException("Could not reach register API", new RuntimeException())))
				.execute(api(), variables());

		assertTrue(Boolean.TRUE.equals((Boolean) store.get(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED)));
		assertFalse(seenSubjects().contains(PSEUDONYM));
		assertEquals(1, startTask.getOutput().size());
		assertTrue(((StringType) startTask.getOutputFirstRep().getValue()).getValue().contains("cycle skipped"));
	}

	@Test
	public void testUnknownPidLeavesSubjectUnseenSoItIsResentAndAudited() throws Exception
	{
		// the register answers 201 / success:true for an unknown PID, only flagging it as a skippable failure — taking
		// that as success would hide the data loss, and the subject must be re-sent once the PID is provisioned
		RegisterAck ack = new RegisterAck(true, List.of(new RegisterAck.Failure("PID not found: ATMP-0001", true)),
				List.of());
		FailingRegisterClient client = new FailingRegisterClient(null, ack);

		new SendToRegister(client).execute(api(), variables());

		assertEquals(1, client.sendCount);
		assertFalse("a skippable failure must not count as transferred", seenSubjects().contains(PSEUDONYM));
		assertEquals(1, startTask.getOutput().size());
		String audit = ((StringType) startTask.getOutputFirstRep().getValue()).getValue();
		assertTrue(audit.contains("PID not found"));
		assertTrue(audit.contains("skippable"));
		assertFalse("a per-subject failure must not abort the cycle",
				Boolean.TRUE.equals((Boolean) store.get(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED)));
	}

	@Test
	public void testParseIssuesInAcknowledgementLeaveSubjectUnseenAndAudited() throws Exception
	{
		// entries the register's own schema rejected: reported inside a 201 with success:true
		RegisterAck ack = new RegisterAck(true, List.of(), List.of("{\"path\":[\"entry\",0,\"resource\",\"meta\"]}"));

		new SendToRegister(new FailingRegisterClient(null, ack)).execute(api(), variables());

		assertFalse(seenSubjects().contains(PSEUDONYM));
		assertEquals(1, startTask.getOutput().size());
		assertTrue(((StringType) startTask.getOutputFirstRep().getValue()).getValue()
				.contains("rejected by the register's schema"));
	}

	@Test
	public void testAuthFailureAbortsCycleInsteadOfFailingEverySubjectSeparately() throws Exception
	{
		new SendToRegister(new FailingRegisterClient(
				new RegisterClient.RegisterCycleException("status 401, E062 wrong apiKey", null)))
				.execute(api(), variables());

		assertTrue(Boolean.TRUE.equals((Boolean) store.get(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED)));
		assertEquals(1, startTask.getOutput().size());
		assertTrue(((StringType) startTask.getOutputFirstRep().getValue()).getValue().contains("cycle skipped"));
	}

	@Test
	public void testBundleRejectionFailsOnlyTheCurrentSubject() throws Exception
	{
		new SendToRegister(new FailingRegisterClient(
				new RegisterClient.RegisterRejectedException("status 400: {\"errorCode\":\"E064\"}", null)))
				.execute(api(), variables());

		assertFalse("a rejected bundle must not abort the cycle",
				Boolean.TRUE.equals((Boolean) store.get(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED)));
		assertFalse(seenSubjects().contains(PSEUDONYM));
		assertEquals(1, startTask.getOutput().size());
		assertTrue(((StringType) startTask.getOutputFirstRep().getValue()).getValue().contains("E064"));
	}

	@Test
	public void testAbortedCycleSkipsSendingAndAuditing() throws Exception
	{
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED, Boolean.TRUE);
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, new ArrayList<>(List.of(PSEUDONYM)));
		FailingRegisterClient client = new FailingRegisterClient(null);

		new SendToRegister(client).execute(api(), variables());

		assertEquals(0, client.sendCount);
		assertFalse(seenSubjects().contains(PSEUDONYM));
		assertTrue("abort is audited once when detected, not per remaining subject", startTask.getOutput().isEmpty());
	}

	@Test
	public void testSubjectErrorFromCreateSkipsSendingWithoutSecondAudit() throws Exception
	{
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT_ERROR, Boolean.TRUE);
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS, new ArrayList<>(List.of(PSEUDONYM)));
		FailingRegisterClient client = new FailingRegisterClient(null);

		new SendToRegister(client).execute(api(), variables());

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
					case "getDsfClientProvider" -> dsfClientProvider();
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private DsfClientProvider dsfClientProvider()
	{
		DsfClient client = (DsfClient) Proxy.newProxyInstance(DsfClient.class.getClassLoader(),
				new Class<?>[] { DsfClient.class }, (proxy, method, args) -> switch (method.getName())
				{
					case "update" -> args[0];
					default -> throw new UnsupportedOperationException(method.getName());
				});

		return (DsfClientProvider) Proxy.newProxyInstance(DsfClientProvider.class.getClassLoader(),
				new Class<?>[] { DsfClientProvider.class }, (proxy, method, args) -> switch (method.getName())
				{
					case "getLocal" -> client;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
