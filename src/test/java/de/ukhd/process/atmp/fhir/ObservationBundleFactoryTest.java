package de.ukhd.process.atmp.fhir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.junit.Test;

public class ObservationBundleFactoryTest
{
	private static final String LOINC_SYSTEM = "http://loinc.org";
	private static final String PSEUDONYM = "ATMP-0001";

	private final ObservationBundleFactory factory = new ObservationBundleFactory(List.of("718-7", "26464-8"));

	@Test
	public void testCreatesCollectionBundleWithOneEntryPerMatchingObservation()
	{
		Bundle bundle = factory
				.createFrom(List.of(labObservation("obs-1", "718-7"), labObservation("obs-2", "26464-8")), PSEUDONYM);

		assertEquals(Bundle.BundleType.COLLECTION, bundle.getType());
		assertEquals(2, bundle.getEntry().size());
	}

	@Test
	public void testExcludesObservationsWithNotConfiguredLoincCode()
	{
		Bundle bundle = factory.createFrom(List.of(labObservation("obs-1", "718-7"), labObservation("obs-2", "999-9")),
				PSEUDONYM);

		assertEquals(1, bundle.getEntry().size());
		assertEquals("obs-1", bundle.getEntry().get(0).getResource().getIdElement().getIdPart());
	}

	@Test
	public void testExcludesObservationsWithStatusNotFinal()
	{
		Observation preliminary = labObservation("obs-1", "718-7").setStatus(Observation.ObservationStatus.PRELIMINARY);

		Bundle bundle = factory.createFrom(List.of(preliminary, labObservation("obs-2", "718-7")), PSEUDONYM);

		assertEquals(1, bundle.getEntry().size());
		assertEquals("obs-2", bundle.getEntry().get(0).getResource().getIdElement().getIdPart());
	}

	@Test
	public void testRewritesSubjectReferenceToPseudonym()
	{
		Bundle bundle = factory.createFrom(List.of(labObservation("obs-1", "718-7")), PSEUDONYM);

		Observation sent = (Observation) bundle.getEntry().get(0).getResource();
		assertEquals("Patient/" + PSEUDONYM, sent.getSubject().getReference());
	}

	@Test
	public void testPreservesSourceObservationIdAndLocalIdentifier()
	{
		Bundle bundle = factory.createFrom(List.of(labObservation("obs-1", "718-7")), PSEUDONYM);

		Observation sent = (Observation) bundle.getEntry().get(0).getResource();
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
		source.getMeta().setVersionId("3").addProfile("http://dic.test/fhir/StructureDefinition/local-lab");
		source.addInterpretation(new CodeableConcept().setText("high"));

		Bundle bundle = factory.createFrom(List.of(source), PSEUDONYM);
		Observation sent = (Observation) bundle.getEntry().get(0).getResource();

		assertFalse(sent.hasEncounter());
		assertFalse(sent.hasPerformer());
		assertFalse(sent.hasNote());
		assertFalse(sent.hasInterpretation());
		assertTrue(sent.getMeta().isEmpty());

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
		source.setValue(null);
		source.addComponent()
				.setCode(new CodeableConcept()
						.addCoding(new org.hl7.fhir.r4.model.Coding().setSystem(LOINC_SYSTEM).setCode("8480-6")))
				.setValue(new Quantity().setValue(120).setUnit("mmHg"));

		Bundle bundle = factory.createFrom(List.of(source), PSEUDONYM);
		Observation sent = (Observation) bundle.getEntry().get(0).getResource();

		assertEquals(1, sent.getComponent().size());
		assertEquals("8480-6", sent.getComponentFirstRep().getCode().getCodingFirstRep().getCode());
	}

	@Test
	public void testEmptyInputProducesEmptyCollectionBundle()
	{
		Bundle bundle = factory.createFrom(List.of(), PSEUDONYM);

		assertEquals(Bundle.BundleType.COLLECTION, bundle.getType());
		assertTrue(bundle.getEntry().isEmpty());
	}

	// --- selection decision: full (bulk) vs incremental query ---

	private static final Instant WATERMARK = Instant.parse("2026-06-01T12:00:00Z");
	private final ObservationBundleFactory bufferedFactory = new ObservationBundleFactory(List.of("718-7"),
			Duration.ofMinutes(1));

	@Test
	public void testFullQueryWhenNoWatermarkYet()
	{
		assertEquals(Optional.empty(), bufferedFactory.queryLowerBound(PSEUDONYM, null, Set.of(PSEUDONYM), false));
	}

	@Test
	public void testFullQueryOnFirstSightEvenWithWatermark()
	{
		// subject not yet in the seen-set -> full (bulk-on-first-sight, covers late enrollment)
		assertEquals(Optional.empty(), bufferedFactory.queryLowerBound(PSEUDONYM, WATERMARK, Set.of(), false));
	}

	@Test
	public void testIncrementalQueryForSeenSubjectUsesWatermarkMinusBuffer()
	{
		Optional<Instant> lowerBound = bufferedFactory.queryLowerBound(PSEUDONYM, WATERMARK, Set.of(PSEUDONYM), false);

		assertEquals(Optional.of(WATERMARK.minus(Duration.ofMinutes(1))), lowerBound);
	}

	@Test
	public void testForceBulkOverridesWatermarkForSeenSubject()
	{
		// even a seen subject with a watermark is queried in full when force-bulk is set
		assertEquals(Optional.empty(), bufferedFactory.queryLowerBound(PSEUDONYM, WATERMARK, Set.of(PSEUDONYM), true));
	}

	private Observation labObservation(String id, String loincCode)
	{
		Observation observation = new Observation();
		observation.setId(id);
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
