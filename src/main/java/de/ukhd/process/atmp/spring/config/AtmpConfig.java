package de.ukhd.process.atmp.spring.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.operaton.bpm.engine.RuntimeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import de.ukhd.process.atmp.client.MedicClient;
import de.ukhd.process.atmp.fhir.ObservationBundleFactory;
import de.ukhd.process.atmp.service.CreateSubjectBundle;
import de.ukhd.process.atmp.service.QueryResearchSubjects;
import de.ukhd.process.atmp.service.RejectDuplicateStart;
import de.ukhd.process.atmp.service.SendToMedic;
import de.ukhd.process.atmp.service.SetTimer;
import dev.dsf.bpe.v2.documentation.ProcessDocumentation;

@Configuration
public class AtmpConfig
{
	@ProcessDocumentation(required = true, processNames = {
			"ukhdde_atmpDataTransfer" }, description = "The id of the local (non DSF) FHIR server holding the ATMP study data, configured in the DSF BPE FHIR client connections config (HAPI or Blaze)", example = "dic-fhir-store")
	@Value("${de.ukhd.atmp.fhir.server.id:#{null}}")
	private String fhirServerId;

	@ProcessDocumentation(required = true, processNames = {
			"ukhdde_atmpDataTransfer" }, description = "Base URL of the MEDIC / integrate-ATMP REST API, the process sends to {url}/api/medic-import", example = "https://medic-staging.dkfz.de")
	@Value("${de.ukhd.atmp.api.url:#{null}}")
	private String apiUrl;

	@ProcessDocumentation(required = true, processNames = {
			"ukhdde_atmpDataTransfer" }, description = "Location of a file (docker secret) containing the site's MEDIC API key, sent as MEDIC-API-KEY header", recommendation = "Use docker secret file to configure", example = "/run/secrets/atmp_medic_api_key")
	@Value("${de.ukhd.atmp.api.key.file:#{null}}")
	private String apiKeyFile;

	@ProcessDocumentation(required = true, processNames = {
			"ukhdde_atmpDataTransfer" }, description = "Comma-separated list of LOINC codes of the laboratory Observations to transfer", example = "718-7,26464-8")
	@Value("${de.ukhd.atmp.observation.loinc.codes:#{null}}")
	private String loincCodes;

	@ProcessDocumentation(processNames = {
			"ukhdde_atmpDataTransfer" }, description = "Identifier system of the ATMP ResearchStudy in the local FHIR store, leave empty to match by identifier value only")
	@Value("${de.ukhd.atmp.study.identifier.system:}")
	private String studyIdentifierSystem;

	@ProcessDocumentation(processNames = {
			"ukhdde_atmpDataTransfer" }, description = "Identifier value of the ATMP ResearchStudy in the local FHIR store")
	@Value("${de.ukhd.atmp.study.identifier.value:ATMP}")
	private String studyIdentifierValue;

	@ProcessDocumentation(processNames = {
			"ukhdde_atmpDataTransfer" }, description = "Default ISO-8601 timer interval used when the start Task has no timer-interval input", example = "PT1H")
	@Value("${de.ukhd.atmp.timer.interval:PT1H}")
	private String timerInterval;

	@ProcessDocumentation(processNames = {
			"ukhdde_atmpDataTransfer" }, description = "ISO-8601 duration subtracted from the watermark on incremental cycles to absorb BPE/FHIR-store clock skew; harmless overlap is absorbed by MEDIC's upsert-by-id", example = "PT1M")
	@Value("${de.ukhd.atmp.watermark.buffer:PT1M}")
	private String watermarkBuffer;

	private List<String> loincCodeList()
	{
		if (loincCodes == null || loincCodes.isBlank())
			throw new IllegalArgumentException("Property de.ukhd.atmp.observation.loinc.codes not set");

		return Arrays.stream(loincCodes.split(",")).map(String::trim).filter(c -> !c.isEmpty()).toList();
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
	public MedicClient medicClient()
	{
		return new MedicClient(apiUrl, apiKeyFile);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
	public ObservationBundleFactory observationBundleFactory()
	{
		return new ObservationBundleFactory(loincCodeList(), Duration.parse(watermarkBuffer));
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public RejectDuplicateStart rejectDuplicateStart(RuntimeService runtimeService)
	{
		return new RejectDuplicateStart(runtimeService);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public SetTimer setTimer()
	{
		return new SetTimer(timerInterval);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public QueryResearchSubjects queryResearchSubjects()
	{
		return new QueryResearchSubjects(fhirServerId, studyIdentifierSystem, studyIdentifierValue);
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public CreateSubjectBundle createSubjectBundle()
	{
		return new CreateSubjectBundle(fhirServerId, loincCodeList(), observationBundleFactory());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public SendToMedic sendToMedic()
	{
		return new SendToMedic(medicClient());
	}
}
