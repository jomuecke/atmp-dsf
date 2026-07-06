package de.ukhd.process.atmp.fhir.profile;

import static org.junit.Assert.assertEquals;

import java.util.Date;
import java.util.List;

import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Task.TaskIntent;
import org.hl7.fhir.r4.model.Task.TaskStatus;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.ValidationResult;
import de.ukhd.process.atmp.AtmpProcessPluginDefinition;
import de.ukhd.process.atmp.ConstantsAtmp;
import dev.dsf.bpe.v2.constants.CodeSystems;
import dev.dsf.bpe.v2.constants.NamingSystems;
import dev.dsf.fhir.validation.ResourceValidator;
import dev.dsf.fhir.validation.ResourceValidatorImpl;
import dev.dsf.fhir.validation.ValidationSupportRule;

public class TaskProfileTest
{
	private static final Logger logger = LoggerFactory.getLogger(TaskProfileTest.class);

	private static final AtmpProcessPluginDefinition def = new AtmpProcessPluginDefinition();

	@ClassRule
	public static final ValidationSupportRule validationRule = new ValidationSupportRule(def.getResourceVersion(),
			def.getResourceReleaseDate(),
			List.of("dsf-task-2.0.0.xml", "task-atmp-data-transfer-start.xml", "task-atmp-data-transfer-stop.xml"),
			List.of("dsf-read-access-tag-2.0.0.xml", "dsf-bpmn-message-2.0.0.xml", "atmp-data-transfer.xml"),
			List.of("dsf-read-access-tag-2.0.0.xml", "dsf-bpmn-message-2.0.0.xml", "atmp-data-transfer.xml"));

	private final ResourceValidator resourceValidator = new ResourceValidatorImpl(validationRule.getFhirContext(),
			validationRule.getValidationSupport());

	@Test
	public void testTaskStartProcessProfileValid()
	{
		Task task = createValidTaskStartProcess();

		ValidationResult result = resourceValidator.validate(task);
		ValidationSupportRule.logValidationMessages(logger, result);

		assertEquals(0, result.getMessages().stream().filter(m -> ResultSeverityEnum.ERROR.equals(m.getSeverity())
				|| ResultSeverityEnum.FATAL.equals(m.getSeverity())).count());
	}

	@Test
	public void testTaskStartProcessProfileValidWithTimerInterval()
	{
		Task task = createValidTaskStartProcess();
		task.addInput().setValue(new StringType("PT15M")).getType().addCoding()
				.setSystem(ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER).setVersion(def.getResourceVersion())
				.setCode(ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER_VALUE_TIMER_INTERVAL);

		ValidationResult result = resourceValidator.validate(task);
		ValidationSupportRule.logValidationMessages(logger, result);

		assertEquals(0, result.getMessages().stream().filter(m -> ResultSeverityEnum.ERROR.equals(m.getSeverity())
				|| ResultSeverityEnum.FATAL.equals(m.getSeverity())).count());
	}

	@Test
	public void testTaskStartProcessProfileValidWithForceBulk()
	{
		Task task = createValidTaskStartProcess();
		task.addInput().setValue(new BooleanType(true)).getType().addCoding()
				.setSystem(ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER).setVersion(def.getResourceVersion())
				.setCode(ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER_VALUE_FORCE_BULK);

		ValidationResult result = resourceValidator.validate(task);
		ValidationSupportRule.logValidationMessages(logger, result);

		assertEquals(0, result.getMessages().stream().filter(m -> ResultSeverityEnum.ERROR.equals(m.getSeverity())
				|| ResultSeverityEnum.FATAL.equals(m.getSeverity())).count());
	}

	@Test
	public void testTaskStartProcessProfileNotValidWithAdditionalInput()
	{
		Task task = createValidTaskStartProcess();
		task.addInput().setValue(new StringType("value")).getType().addCoding()
				.setSystem(ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER).setVersion(def.getResourceVersion())
				.setCode(ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER_VALUE_TIMER_INTERVAL);
		task.addInput().setValue(new StringType("other")).getType().addCoding()
				.setSystem(ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER).setVersion(def.getResourceVersion())
				.setCode(ConstantsAtmp.CODESYSTEM_ATMP_DATA_TRANSFER_VALUE_FIRST_EXECUTION);

		ValidationResult result = resourceValidator.validate(task);
		ValidationSupportRule.logValidationMessages(logger, result);

		assertEquals(1, result.getMessages().stream().filter(m -> ResultSeverityEnum.ERROR.equals(m.getSeverity())
				|| ResultSeverityEnum.FATAL.equals(m.getSeverity())).count());
	}

	private Task createValidTaskStartProcess()
	{
		Task task = new Task();
		task.getMeta().addProfile(ConstantsAtmp.PROFILE_TASK_ATMP_DATA_TRANSFER_START + "|" + def.getResourceVersion());
		task.setInstantiatesCanonical(
				ConstantsAtmp.PROFILE_TASK_ATMP_DATA_TRANSFER_START_PROCESS_URI + "|" + def.getResourceVersion());
		task.setStatus(TaskStatus.REQUESTED);
		task.setIntent(TaskIntent.ORDER);
		task.setAuthoredOn(new Date());
		task.getRequester().setType(ResourceType.Organization.name())
				.setIdentifier(NamingSystems.OrganizationIdentifier.withValue("Test_DIC1"));
		task.getRestriction().addRecipient().setType(ResourceType.Organization.name())
				.setIdentifier(NamingSystems.OrganizationIdentifier.withValue("Test_DIC1"));

		task.addInput().setValue(new StringType(ConstantsAtmp.PROFILE_TASK_ATMP_DATA_TRANSFER_START_MESSAGE_NAME))
				.getType().addCoding(CodeSystems.BpmnMessage.messageName());

		return task;
	}

	@Test
	public void testTaskStopProcessProfileValid()
	{
		Task task = createValidTaskStopProcess();

		ValidationResult result = resourceValidator.validate(task);
		ValidationSupportRule.logValidationMessages(logger, result);

		assertEquals(0, result.getMessages().stream().filter(m -> ResultSeverityEnum.ERROR.equals(m.getSeverity())
				|| ResultSeverityEnum.FATAL.equals(m.getSeverity())).count());
	}

	private Task createValidTaskStopProcess()
	{
		Task task = new Task();
		task.getMeta().addProfile(ConstantsAtmp.PROFILE_TASK_ATMP_DATA_TRANSFER_STOP + "|" + def.getResourceVersion());
		task.setInstantiatesCanonical(
				ConstantsAtmp.PROFILE_TASK_ATMP_DATA_TRANSFER_STOP_PROCESS_URI + "|" + def.getResourceVersion());
		task.setStatus(TaskStatus.REQUESTED);
		task.setIntent(TaskIntent.ORDER);
		task.setAuthoredOn(new Date());
		task.getRequester().setType(ResourceType.Organization.name())
				.setIdentifier(NamingSystems.OrganizationIdentifier.withValue("Test_DIC1"));
		task.getRestriction().addRecipient().setType(ResourceType.Organization.name())
				.setIdentifier(NamingSystems.OrganizationIdentifier.withValue("Test_DIC1"));

		task.addInput().setValue(new StringType(ConstantsAtmp.PROFILE_TASK_ATMP_DATA_TRANSFER_STOP_MESSAGE_NAME))
				.getType().addCoding(CodeSystems.BpmnMessage.messageName());

		return task;
	}
}
