package de.ukhd.process.atmp.spring.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import de.ukhd.process.atmp.client.RegisterClient;
import de.ukhd.process.atmp.fhir.ObservationBundleFactory;
import de.ukhd.process.atmp.service.CreateSubjectBundle;
import de.ukhd.process.atmp.service.QueryResearchSubjects;
import de.ukhd.process.atmp.service.RejectDuplicateStart;
import de.ukhd.process.atmp.service.SendToRegister;
import de.ukhd.process.atmp.service.SetTimer;
import dev.dsf.bpe.v2.ProcessPluginApi;
import dev.dsf.bpe.v2.documentation.ProcessDocumentation;

@Configuration
public class AtmpConfig
{
	@ProcessDocumentation(required = true, processNames = {
			"ukhdde_atmpDataTransfer" }, description = "The id of the local (non DSF) FHIR server holding the ATMP study data, configured in the DSF BPE FHIR client connections config (HAPI or Blaze)", example = "dic-fhir-store")
	@Value("${de.ukhd.atmp.fhir.server.id:#{null}}")
	private String fhirServerId;

	@ProcessDocumentation(required = true, processNames = {
			"ukhdde_atmpDataTransfer" }, description = "Complete URL of the MEDIC / integrate-ATMP endpoint that accepts collection Bundles; the process posts to this exact URL without appending a path", example = "https://medic-staging.dkfz.de/medic-import")
	@Value("${de.ukhd.atmp.api.url:#{null}}")
	private String importEndpointUrl;

	@ProcessDocumentation(processNames = {
			"ukhdde_atmpDataTransfer" }, description = "BHZ identity used with the raw register API key to construct the Base64 MEDIC-API-KEY header; leave empty only when the configured key file already contains the complete encoded header value", example = "bhz-demo")
	@Value("${de.ukhd.atmp.api.bhz:}")
	private String apiBhz;

	@ProcessDocumentation(required = true, processNames = {
			"ukhdde_atmpDataTransfer" }, description = "Location of a docker-secret file containing the raw register API key when a BHZ is configured, or the complete encoded MEDIC-API-KEY header value in legacy mode", recommendation = "Configure a BHZ and store only the raw API key in this file", example = "/run/secrets/atmp_medic_api_key")
	@Value("${de.ukhd.atmp.api.key.file:#{null}}")
	private String apiKeyFile;

	@ProcessDocumentation(required = true, processNames = {
			"ukhdde_atmpDataTransfer" }, description = "Comma-separated list of LOINC codes of the laboratory Observations to transfer", example = "718-7,26464-8")
	@Value("${de.ukhd.atmp.observation.loinc.codes:#{null}}")
	private String loincCodes;

	@ProcessDocumentation(processNames = {
			"ukhdde_atmpDataTransfer" }, description = "ISO-8601 timeout for establishing the connection to the register API", example = "PT10S")
	@Value("${de.ukhd.atmp.api.connect.timeout:PT10S}")
	private String apiConnectTimeout;

	@ProcessDocumentation(processNames = {
			"ukhdde_atmpDataTransfer" }, description = "ISO-8601 timeout for a complete register API request/response; without it a hung register would block the cycle indefinitely", example = "PT60S")
	@Value("${de.ukhd.atmp.api.request.timeout:PT60S}")
	private String apiRequestTimeout;

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
			"ukhdde_atmpDataTransfer" }, description = "ISO-8601 duration subtracted from the watermark on incremental cycles to absorb BPE/FHIR-store clock skew; harmless overlap is absorbed by the register's upsert-by-id", example = "PT1M")
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
	public RegisterClient registerClient(ProcessPluginApi api)
	{
		return new RegisterClient(importEndpointUrl, apiBhz, apiKeyFile, Duration.parse(apiConnectTimeout),
				Duration.parse(apiRequestTimeout), api.getProxyConfig());
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
	public ObservationBundleFactory observationBundleFactory()
	{
		return new ObservationBundleFactory(loincCodeList(), Duration.parse(watermarkBuffer));
	}

	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	public RejectDuplicateStart rejectDuplicateStart()
	{
		return new RejectDuplicateStart();
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
	public SendToRegister sendToRegister(RegisterClient registerClient)
	{
		return new SendToRegister(registerClient);
	}
}
