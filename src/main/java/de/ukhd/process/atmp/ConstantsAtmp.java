package de.ukhd.process.atmp;

public interface ConstantsAtmp
{
	// Process identity. The BPE derives the full process id from the ActivityDefinition URL host,
	// so the name base "ukhdde_" must correspond to the "http://ukhd.de" artifact URLs below.
	String PROCESS_ATMP_BASE = "ukhdde_";
	String PROCESS_NAME_ATMP_DATA_TRANSFER = "atmpDataTransfer";
	String PROCESS_NAME_FULL_ATMP_DATA_TRANSFER = PROCESS_ATMP_BASE + PROCESS_NAME_ATMP_DATA_TRANSFER;

	String PROCESS_ATMP_URI_BASE = "http://ukhd.de/bpe/Process/";
	String PROCESS_ATMP_URI_DATA_TRANSFER = PROCESS_ATMP_URI_BASE + PROCESS_NAME_ATMP_DATA_TRANSFER;

	// Start message / Task profile
	String PROFILE_TASK_ATMP_DATA_TRANSFER_START = "http://ukhd.de/fhir/StructureDefinition/task-atmp-data-transfer-start";
	String PROFILE_TASK_ATMP_DATA_TRANSFER_START_PROCESS_URI = PROCESS_ATMP_URI_DATA_TRANSFER;
	String PROFILE_TASK_ATMP_DATA_TRANSFER_START_MESSAGE_NAME = "atmpDataTransferStart";

	// Stop message / Task profile (clean termination of the running loop)
	String PROFILE_TASK_ATMP_DATA_TRANSFER_STOP = "http://ukhd.de/fhir/StructureDefinition/task-atmp-data-transfer-stop";
	String PROFILE_TASK_ATMP_DATA_TRANSFER_STOP_PROCESS_URI = PROCESS_ATMP_URI_DATA_TRANSFER;
	String PROFILE_TASK_ATMP_DATA_TRANSFER_STOP_MESSAGE_NAME = "atmpDataTransferStop";

	// CodeSystem + codes for Task inputs / status
	String CODESYSTEM_ATMP_DATA_TRANSFER = "http://ukhd.de/fhir/CodeSystem/atmp-data-transfer";
	String CODESYSTEM_ATMP_DATA_TRANSFER_VALUE_TIMER_INTERVAL = "timer-interval";
	String CODESYSTEM_ATMP_DATA_TRANSFER_VALUE_ERROR = "error";

	// Default cycle timer interval (ISO-8601 duration) when no Task input / env override is given
	String TIMER_INTERVAL_DEFAULT_VALUE = "PT1H";

	// BPMN process variables carried across timer cycles
	String BPMN_EXECUTION_VARIABLE_TIMER_INTERVAL = "atmpTimerInterval";
	String BPMN_EXECUTION_VARIABLE_DUPLICATE_START = "atmpDuplicateStart";
	// True once a cycle-level failure (e.g. register unreachable) was detected mid-cycle: the remaining subjects of the
	// cycle are skipped without individual audit entries and the watermark is not advanced next cycle
	String BPMN_EXECUTION_VARIABLE_CYCLE_ABORTED = "atmpCycleAborted";
	// ISO-8601 instant watermark = start time of the last completed cycle (incremental lower bound source)
	String BPMN_EXECUTION_VARIABLE_WATERMARK = "atmpWatermark";
	// ISO-8601 instant recorded at the start of the current cycle; promoted to the watermark next cycle
	String BPMN_EXECUTION_VARIABLE_CYCLE_START = "atmpCycleStart";
	String BPMN_EXECUTION_VARIABLE_SEEN_SUBJECTS = "atmpSeenSubjects";
	String BPMN_EXECUTION_VARIABLE_RESEARCH_SUBJECTS = "atmpResearchSubjects";
	String BPMN_EXECUTION_VARIABLE_SUBJECT = "atmpSubject";
	String BPMN_EXECUTION_VARIABLE_SUBJECT_BUNDLE = "atmpSubjectBundle";
	// Per multi-instance subject flag: true when creating the subject's bundle failed, so sending is skipped this cycle
	String BPMN_EXECUTION_VARIABLE_SUBJECT_ERROR = "atmpSubjectError";

	// Entries of the atmpResearchSubjects list are "<patient-reference>|<pseudonym>"
	String RESEARCH_SUBJECT_ENTRY_SEPARATOR = "|";
	String BPMN_EXECUTION_VARIABLE_ERROR = "atmpError";
	String BPMN_EXECUTION_VARIABLE_ERROR_MESSAGE = "atmpErrorMessage";
}
