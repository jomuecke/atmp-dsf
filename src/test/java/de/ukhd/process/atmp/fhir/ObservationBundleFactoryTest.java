package de.ukhd.process.atmp.fhir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.junit.Test;

import de.ukhd.process.atmp.fhir.ObservationBundleFactory.RejectedObservation;
import de.ukhd.process.atmp.fhir.ObservationBundleFactory.SubjectBundle;

public class ObservationBundleFactoryTest
{
	private static final String LOINC_SYSTEM = "http://loinc.org";
	private static final String PSEUDONYM = "ATMP-0001";

	// fixed clock in a positive-offset zone: the register requires an explicit "+HH:mm" offset and rejects "Z"
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-23T10:00:00Z"), ZoneId.of("Europe/Berlin"));

	private final ObservationBundleFactory factory = new ObservationBundleFactory(List.of("718-7", "26464-8"),
			Duration.ZERO, CLOCK);

	@Test
	public void testCreatesCollectionBundleWithOneEntryPerMatchingObservation()
	{
		Bundle bundle = bundleOf(labObservation("obs-1", "718-7"), labObservation("obs-2", "26464-8"));

		assertEquals(Bundle.BundleType.COLLECTION, bundle.getType());
		assertEquals(2, bundle.getEntry().size());
	}

	@Test
	public void testExcludesObservationsWithNotConfiguredLoincCode()
	{
		Bundle bundle = bundleOf(labObservation("obs-1", "718-7"), labObservation("obs-2", "999-9"));

		assertEquals(1, bundle.getEntry().size());
		assertEquals("obs-1", bundle.getEntry().get(0).getResource().getIdElement().getIdPart());
	}

	@Test
	public void testExcludesObservationsWithStatusNotFinal()
	{
		Observation preliminary = labObservation("obs-1", "718-7").setStatus(Observation.ObservationStatus.PRELIMINARY);

		Bundle bundle = bundleOf(preliminary, labObservation("obs-2", "718-7"));

		assertEquals(1, bundle.getEntry().size());
		assertEquals("obs-2", bundle.getEntry().get(0).getResource().getIdElement().getIdPart());
	}

	@Test
	public void testRewritesSubjectReferenceToPseudonym()
	{
		assertEquals("Patient/" + PSEUDONYM,
				sentObservation(labObservation("obs-1", "718-7")).getSubject().getReference());
	}

	@Test
	public void testPreservesSourceObservationIdAndLocalIdentifier()
	{
		Observation sent = sentObservation(labObservation("obs-1", "718-7"));

		assertEquals("obs-1", sent.getIdElement().getIdPart());
		assertEquals(1, sent.getIdentifier().size());
		assertEquals("http://dic.test/sid/lab-result-id", sent.getIdentifier().get(0).getSystem());
		assertEquals("local-obs-1", sent.getIdentifier().get(0).getValue());
	}

	@Test
	public void testMinimizesToAgreedFields()
	{
		Observation source = labObservation("obs-1", "718-7");
		source.setEncounter(new Reference("Encounter/enc-1"));
		source.addPerformer(new Reference("Practitioner/prac-1"));
		source.addNote(new Annotation().setText("internal comment"));
		source.addInterpretation(new CodeableConcept().setText("high"));

		Observation sent = sentObservation(source);

		assertFalse(sent.hasEncounter());
		assertFalse(sent.hasPerformer());
		assertFalse(sent.hasNote());
		assertFalse(sent.hasInterpretation());

		assertEquals(Observation.ObservationStatus.FINAL, sent.getStatus());
		assertEquals("laboratory", sent.getCategoryFirstRep().getCodingFirstRep().getCode());
		assertEquals("718-7", sent.getCode().getCodingFirstRep().getCode());
		assertTrue(sent.hasEffectiveDateTimeType());
		assertEquals("2026-01-15T10:00:00+01:00", sent.getEffectiveDateTimeType().getValueAsString());
		assertEquals("g/dL", sent.getValueQuantity().getUnit());
	}

	@Test
	public void testPreservesComponents()
	{
		Observation source = labObservation("obs-1", "718-7");
		source.addComponent()
				.setCode(new CodeableConcept()
						.addCoding(new org.hl7.fhir.r4.model.Coding().setSystem(LOINC_SYSTEM).setCode("8480-6")))
				.setValue(new Quantity().setValue(120).setUnit("mmHg"));

		Observation sent = sentObservation(source);

		assertEquals(1, sent.getComponent().size());
		assertEquals("8480-6", sent.getComponentFirstRep().getCode().getCodingFirstRep().getCode());
	}

	@Test
	public void testEmptyInputProducesEmptyCollectionBundle()
	{
		Bundle bundle = factory.createFrom(List.of(), PSEUDONYM).bundle();

		assertEquals(Bundle.BundleType.COLLECTION, bundle.getType());
		assertTrue(bundle.getEntry().isEmpty());
	}

	// --- register import schema (medic-import.yaml), stricter than FHIR R4 itself ---

	@Test
	public void testBundleCarriesIdAndLastUpdatedWithNumericOffset()
	{
		Bundle bundle = bundleOf(labObservation("obs-1", "718-7"));

		assertEquals("Bundle" + Instant.parse("2026-07-23T10:00:00Z").toEpochMilli(),
				bundle.getIdElement().getIdPart());
		assertEquals("2026-07-23T12:00:00.000+02:00", bundle.getMeta().getLastUpdatedElement().getValueAsString());
	}

	@Test
	public void testForwardsLocalMetaVersionIdLastUpdatedAndSourceButDropsProfile()
	{
		Observation source = labObservation("obs-1", "718-7");
		source.getMeta().addProfile("http://dic.test/fhir/StructureDefinition/local-lab");

		Observation sent = sentObservation(source);

		assertEquals("3", sent.getMeta().getVersionId());
		assertEquals("#3d14d35eb0a622ea", sent.getMeta().getSource());
		assertNotNull(sent.getMeta().getLastUpdated());
		assertTrue("local profiles are not forwarded to the register", sent.getMeta().getProfile().isEmpty());
	}

	@Test
	public void testRewritesZuluTimestampsToAnExplicitOffsetPreservingTheInstant()
	{
		Observation source = labObservation("obs-1", "718-7");
		source.setEffective(new DateTimeType("2026-01-15T09:00:00Z"));
		source.getMeta().setLastUpdatedElement(new InstantType("2026-01-15T09:30:00.123Z"));

		Observation sent = sentObservation(source);

		assertEquals("2026-01-15T10:00:00+01:00", sent.getEffectiveDateTimeType().getValueAsString());
		assertEquals("2026-01-15T10:30:00.123+01:00", sent.getMeta().getLastUpdatedElement().getValueAsString());
	}

	@Test
	public void testRejectsObservationWithoutMetaSourceInsteadOfSendingIt()
	{
		Observation source = labObservation("obs-1", "718-7");
		source.getMeta().setSource(null);

		SubjectBundle result = factory.createFrom(List.of(source), PSEUDONYM);

		assertTrue(result.bundle().getEntry().isEmpty());
		assertEquals(1, result.rejected().size());
		assertEquals("obs-1", result.rejected().get(0).observationId());
		assertTrue(result.rejected().get(0).reason().contains("meta.source"));
	}

	@Test
	public void testRejectsObservationWithoutCompleteValueQuantity()
	{
		Observation noValue = labObservation("obs-1", "718-7");
		noValue.setValue(null);

		Observation incompleteValue = labObservation("obs-2", "718-7");
		incompleteValue.setValue(new Quantity().setValue(13.5).setUnit("g/dL"));

		Observation codedValue = labObservation("obs-3", "718-7");
		codedValue.setValue(new CodeableConcept().setText("positive"));

		SubjectBundle result = factory.createFrom(List.of(noValue, incompleteValue, codedValue), PSEUDONYM);

		assertTrue(result.bundle().getEntry().isEmpty());
		assertEquals(3, result.rejected().size());
		assertTrue(result.rejected().get(0).reason().contains("valueQuantity"));
		assertTrue(result.rejected().get(1).reason().contains("value, unit, system and code"));
		assertTrue(result.rejected().get(2).reason().contains("CodeableConcept"));
	}

	@Test
	public void testRejectsEffectiveDateTimeWithoutSecondPrecision()
	{
		Observation dateOnly = labObservation("obs-1", "718-7");
		dateOnly.setEffective(new DateTimeType("2026-01-15"));

		SubjectBundle result = factory.createFrom(List.of(dateOnly), PSEUDONYM);

		assertTrue(result.bundle().getEntry().isEmpty());
		assertEquals(1, result.rejected().size());
		assertTrue(result.rejected().get(0).reason().contains("effectiveDateTime"));
	}

	@Test
	public void testRejectsEffectivePeriodBecauseTheRegisterOnlyAcceptsEffectiveDateTime()
	{
		Observation period = labObservation("obs-1", "718-7");
		period.setEffective(
				new org.hl7.fhir.r4.model.Period().setStartElement(new DateTimeType("2026-01-15T10:00:00+01:00")));

		SubjectBundle result = factory.createFrom(List.of(period), PSEUDONYM);

		assertEquals(1, result.rejected().size());
		assertTrue(result.rejected().get(0).reason().contains("Period"));
	}

	@Test
	public void testSendsTheConformingObservationsOfASubjectEvenIfOthersAreRejected()
	{
		Observation broken = labObservation("obs-1", "718-7");
		broken.getMeta().setVersionId(null);

		SubjectBundle result = factory.createFrom(List.of(broken, labObservation("obs-2", "718-7")), PSEUDONYM);

		assertEquals(1, result.bundle().getEntry().size());
		assertEquals("obs-2", result.bundle().getEntry().get(0).getResource().getIdElement().getIdPart());
		assertEquals(1, result.rejected().size());
	}

	@Test
	public void testRejectsAPseudonymTheRegisterWouldNotAcceptAsASubjectReference()
	{
		// the register validates subject.reference against ^Patient/[\w\d-]{0,30}$
		assertThrows(IllegalArgumentException.class,
				() -> factory.createFrom(List.of(labObservation("obs-1", "718-7")), "ATMP:0001"));
		assertThrows(IllegalArgumentException.class,
				() -> factory.createFrom(List.of(labObservation("obs-1", "718-7")), "A".repeat(31)));
	}

	@Test
	public void testRejectedObservationDescribesItselfWithIdAndReason()
	{
		assertEquals("obs-1: some reason", new RejectedObservation("obs-1", "some reason").toString());
		assertEquals("<no id>: some reason", new RejectedObservation(null, "some reason").toString());
	}

	// --- selection decision: full (bulk) vs incremental query ---

	private static final Instant WATERMARK = Instant.parse("2026-06-01T12:00:00Z");
	private final ObservationBundleFactory bufferedFactory = new ObservationBundleFactory(List.of("718-7"),
			Duration.ofMinutes(1));

	@Test
	public void testFullQueryWhenNoWatermarkYet()
	{
		assertEquals(Optional.empty(), bufferedFactory.queryLowerBound(PSEUDONYM, null, Set.of(PSEUDONYM)));
	}

	@Test
	public void testFullQueryOnFirstSightEvenWithWatermark()
	{
		// subject not yet in the seen-set -> full (bulk-on-first-sight, covers late enrollment)
		assertEquals(Optional.empty(), bufferedFactory.queryLowerBound(PSEUDONYM, WATERMARK, Set.of()));
	}

	@Test
	public void testIncrementalQueryForSeenSubjectUsesWatermarkMinusBuffer()
	{
		Optional<Instant> lowerBound = bufferedFactory.queryLowerBound(PSEUDONYM, WATERMARK, Set.of(PSEUDONYM));

		assertEquals(Optional.of(WATERMARK.minus(Duration.ofMinutes(1))), lowerBound);
	}

	private Bundle bundleOf(Observation... observations)
	{
		SubjectBundle result = factory.createFrom(List.of(observations), PSEUDONYM);
		assertEquals("no Observation of this test was expected to be rejected", List.of(), result.rejected());
		return result.bundle();
	}

	private Observation sentObservation(Observation source)
	{
		return (Observation) bundleOf(source).getEntry().get(0).getResource();
	}

	private Observation labObservation(String id, String loincCode)
	{
		Observation observation = new Observation();
		observation.setId(id);
		observation.getMeta().setVersionId("3").setSource("#3d14d35eb0a622ea")
				.setLastUpdatedElement(new InstantType("2026-01-15T10:05:00.000+01:00"));
		observation.setStatus(Observation.ObservationStatus.FINAL);
		observation.addIdentifier().setSystem("http://dic.test/sid/lab-result-id").setValue("local-" + id);
		observation.addCategory().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
				.setCode("laboratory");
		observation.getCode().addCoding().setSystem(LOINC_SYSTEM).setCode(loincCode);
		observation.setSubject(new Reference("Patient/local-patient-1"));
		observation.setEffective(new DateTimeType("2026-01-15T10:00:00+01:00"));
		observation.setValue(
				new Quantity().setValue(13.5).setUnit("g/dL").setSystem("http://unitsofmeasure.org").setCode("g/dL"));

		return observation;
	}
}
