package de.ukhd.process.atmp;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import de.ukhd.process.atmp.spring.config.AtmpConfig;
import dev.dsf.bpe.v2.ProcessPluginDefinition;

public class AtmpProcessPluginDefinition implements ProcessPluginDefinition
{
	@SuppressWarnings("PMD.AvoidUsingHardCodedIP") // Semantic version, not an IP address
	public static final String VERSION = "1.0.0.0";
	public static final LocalDate RELEASE_DATE = LocalDate.of(2026, 8, 31);

	@Override
	public String getName()
	{
		return "atmp-dsf";
	}

	@Override
	public String getVersion()
	{
		return VERSION;
	}

	@Override
	public LocalDate getReleaseDate()
	{
		return RELEASE_DATE;
	}

	@Override
	public List<String> getProcessModels()
	{
		return List.of("bpe/atmp-data-transfer.bpmn");
	}

	@Override
	public List<Class<?>> getSpringConfigurations()
	{
		return List.of(AtmpConfig.class);
	}

	@Override
	public Map<String, List<String>> getFhirResourcesByProcessId()
	{
		var aDataTransfer = "fhir/ActivityDefinition/atmp-data-transfer.xml";
		var cDataTransfer = "fhir/CodeSystem/atmp-data-transfer.xml";
		var sStart = "fhir/StructureDefinition/task-atmp-data-transfer-start.xml";
		var sStop = "fhir/StructureDefinition/task-atmp-data-transfer-stop.xml";
		var tStart = "fhir/Task/task-atmp-data-transfer-start.xml";
		var tStop = "fhir/Task/task-atmp-data-transfer-stop.xml";
		var vDataTransfer = "fhir/ValueSet/atmp-data-transfer.xml";

		return Map.of(ConstantsAtmp.PROCESS_NAME_FULL_ATMP_DATA_TRANSFER,
				List.of(aDataTransfer, cDataTransfer, sStart, sStop, tStart, tStop, vDataTransfer));
	}
}
