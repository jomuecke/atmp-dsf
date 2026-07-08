package de.ukhd.process.atmp.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.junit.Test;

import de.ukhd.process.atmp.ConstantsAtmp;
import de.ukhd.process.atmp.fhir.ObservationBundleFactory;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.service.FhirClientProvider;
import dev.dsf.bpe.v2.variables.Variables;

/**
 * Behaviour test for the Issue D error wiring of {@link CreateSubjectBundle}: a per-subject failure is audited on the
 * start Task (with the local patient identity redacted) and flags the subject so {@link SendToMedic} skips it, and an
 * aborted cycle skips the remaining subjects entirely.
 */
public class CreateSubjectBundleTest
{
	private static final String PSEUDONYM = "ATMP-0001";
	private static final String PATIENT_REFERENCE = "Patient/local-patient-1";
	private static final String SUBJECT_ENTRY = PATIENT_REFERENCE + ConstantsAtmp.RESEARCH_SUBJECT_ENTRY_SEPARATOR
			+ PSEUDONYM;

	private final Map<String, Object> store = new HashMap<>();
	private final Task startTask = new Task();

	private final CreateSubjectBundle service = new CreateSubjectBundle("dic-fhir-store", List.of("718-7"),
			new ObservationBundleFactory(List.of("718-7")));

	@Test
	public void testFailureAuditsRedactedErrorAndFlagsSubject() throws Exception
	{
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT, SUBJECT_ENTRY);

		// no FHIR client configured -> bundle creation fails for this subject
		service.execute(api(), variables());

		assertEquals(Boolean.TRUE, store.get(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT_ERROR));
		assertEquals(1, startTask.getOutput().size());
		String audit = ((StringType) startTask.getOutputFirstRep().getValue()).getValue();
		assertTrue(audit.contains(PSEUDONYM));
		assertTrue("local patient identity must not leak into the audit output", !audit.contains("local-patient-1"));
	}

	@Test
	public void testAbortedCycleSkipsSubjectWithoutErrorOrAudit() throws Exception
	{
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT, SUBJECT_ENTRY);
		store.put(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED, Boolean.TRUE);

		service.execute(api(), variables());

		assertEquals(Boolean.FALSE, store.get(ConstantsAtmp.BPMN_EXECUTION_VARIABLE_SUBJECT_ERROR));
		assertTrue("aborted cycle must not produce per-subject audit noise", startTask.getOutput().isEmpty());
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
		FhirClientProvider clientProvider = (FhirClientProvider) Proxy.newProxyInstance(
				FhirClientProvider.class.getClassLoader(), new Class<?>[] { FhirClientProvider.class },
				(proxy, method, args) -> Optional.empty());

		return (ProcessPluginApi) Proxy.newProxyInstance(ProcessPluginApi.class.getClassLoader(),
				new Class<?>[] { ProcessPluginApi.class }, (proxy, method, args) -> switch (method.getName())
				{
					case "getFhirClientProvider" -> clientProvider;
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
