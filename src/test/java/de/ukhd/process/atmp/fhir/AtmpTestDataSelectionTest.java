package de.ukhd.process.atmp.fhir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.junit.Test;

import ca.uhn.fhir.context.FhirContext;

/** Verifies that both manual E2E seed bundles contain the same diagnostic LOINC-selection matrix. */
public class AtmpTestDataSelectionTest
{
	private static final List<String> BASELINE_LOINC_CODES = List.of("6690-2", "718-7", "777-3");
	private static final String EXPANDED_LOINC_CODE = "2093-3";
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.ofHours(2));

	private static final List<SiteFixture> SITE_FIXTURES = List.of(
			new SiteFixture(Path.of("test/ressources/atmp_fhir_store_test_bundle.json"), "Patient/patient-0000123456",
					"code-01", "SWL-2507011234-2025-07-01-1", "SWL-2506151234-2025-06-15-2"),
			new SiteFixture(Path.of("test/ressources/atmp_fhir_store_test_bundle_dic2.json"),
					"Patient/patient-0000765432", "code-11", "MRI-2507011111-2025-07-01-1",
					"MRI-2506151111-2025-06-15-4"));

	@Test
	public void testBaselineLoincConfigurationSendsOnlyTheConfiguredFinalObservation() throws IOException
	{
		ObservationBundleFactory factory = factory(BASELINE_LOINC_CODES);

		for (SiteFixture site : SITE_FIXTURES)
			assertEquals(site.path().toString(), Set.of(site.baselineObservationId()),
					sentIds(factory, observationsForCohortPatient(site), site.pseudonym()));
	}

	@Test
	public void testExpandedLoincConfigurationAddsOnlyTheHistoricalCholesterolObservation() throws IOException
	{
		ObservationBundleFactory factory = factory(List.of("6690-2", "718-7", "777-3", EXPANDED_LOINC_CODE));

		for (SiteFixture site : SITE_FIXTURES)
			assertEquals(site.path().toString(), Set.of(site.baselineObservationId(), site.cholesterolObservationId()),
					sentIds(factory, observationsForCohortPatient(site), site.pseudonym()));
	}

	@Test
	public void testClientAssignedFixtureIdsAreAcceptedByHapi() throws IOException
	{
		for (SiteFixture site : SITE_FIXTURES)
		{
			Bundle transactionBundle;
			try (var reader = Files.newBufferedReader(site.path()))
			{
				transactionBundle = FhirContext.forR4Cached().newJsonParser().parseResource(Bundle.class, reader);
			}

			for (Bundle.BundleEntryComponent entry : transactionBundle.getEntry())
			{
				String id = entry.getResource().getIdElement().getIdPart();
				assertTrue(site.path() + ": client-assigned id must contain a non-numeric character: " + id,
						id != null && !id.matches("[0-9]+"));
			}
		}
	}

	private ObservationBundleFactory factory(List<String> loincCodes)
	{
		return new ObservationBundleFactory(loincCodes, Duration.ZERO, CLOCK);
	}

	private List<Observation> observationsForCohortPatient(SiteFixture site) throws IOException
	{
		Bundle transactionBundle;
		try (var reader = Files.newBufferedReader(site.path()))
		{
			transactionBundle = FhirContext.forR4Cached().newJsonParser().parseResource(Bundle.class, reader);
		}

		return transactionBundle.getEntry().stream().filter(Bundle.BundleEntryComponent::hasResource)
				.map(Bundle.BundleEntryComponent::getResource).filter(Observation.class::isInstance)
				.map(Observation.class::cast).filter(o -> site.patientReference().equals(o.getSubject().getReference()))
				.toList();
	}

	private Set<String> sentIds(ObservationBundleFactory factory, List<Observation> observations, String pseudonym)
	{
		return factory.createFrom(observations, pseudonym).bundle().getEntry().stream()
				.map(e -> e.getResource().getIdElement().getIdPart()).collect(Collectors.toSet());
	}

	private record SiteFixture(Path path, String patientReference, String pseudonym, String baselineObservationId,
			String cholesterolObservationId)
	{
	}
}
