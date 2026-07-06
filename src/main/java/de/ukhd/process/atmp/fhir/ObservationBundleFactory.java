package de.ukhd.process.atmp.fhir;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;

/**
 * Pure domain core of the ATMP data transfer: selects the laboratory {@link Observation}s to send (configured LOINC
 * codes, status {@code final}) and transforms them into one pseudonymized FHIR {@code collection} {@link Bundle} per
 * study participant.
 *
 * <p>
 * Per Observation: {@code subject.reference} is rewritten to {@code Patient/<pseudonym>}, the source
 * {@code Observation.id} is preserved unchanged (MEDIC upserts by id), the local {@code Observation.identifier} is
 * kept, and all other fields are minimized to the agreed set: id, status, category, code, subject, effective[x],
 * value[x] and component.
 */
public class ObservationBundleFactory
{
	public static final String LOINC_SYSTEM = "http://loinc.org";

	private final Set<String> loincCodes;
	private final Duration watermarkBuffer;

	public ObservationBundleFactory(Collection<String> loincCodes)
	{
		this(loincCodes, Duration.ZERO);
	}

	public ObservationBundleFactory(Collection<String> loincCodes, Duration watermarkBuffer)
	{
		Objects.requireNonNull(loincCodes, "loincCodes");
		Objects.requireNonNull(watermarkBuffer, "watermarkBuffer");
		this.loincCodes = Set.copyOf(loincCodes);
		this.watermarkBuffer = watermarkBuffer;
	}

	/**
	 * Decides the {@code Observation._lastUpdated} lower bound for querying a single subject's laboratory results:
	 * {@link Optional#empty()} means a <b>full</b> (un-watermarked) query — everything for the subject — while a
	 * present value means an <b>incremental</b> query for {@code _lastUpdated} greater than that instant.
	 *
	 * <p>
	 * A full query is used when a full re-send is forced, when no cycle has completed yet (no watermark), or the first
	 * time a subject is seen in this instance (bulk-on-first-sight, covers late enrollment). Otherwise the incremental
	 * bound is the watermark minus the configured buffer, which absorbs BPE&harr;FHIR-store clock skew; any resulting
	 * re-sends are harmless because MEDIC upserts by {@code Observation.id}.
	 *
	 * @param pseudonym
	 *            the subject's ATMP pseudonym, not <code>null</code>
	 * @param watermark
	 *            start instant of the last completed cycle, or <code>null</code> if none completed yet
	 * @param seenSubjects
	 *            pseudonyms already handled in this instance, not <code>null</code>
	 * @param forceBulk
	 *            <code>true</code> to ignore the watermark and re-send everything
	 * @return the incremental lower bound, or {@link Optional#empty()} for a full query
	 */
	public Optional<Instant> queryLowerBound(String pseudonym, Instant watermark, Collection<String> seenSubjects,
			boolean forceBulk)
	{
		Objects.requireNonNull(pseudonym, "pseudonym");
		Objects.requireNonNull(seenSubjects, "seenSubjects");

		if (forceBulk || watermark == null || !seenSubjects.contains(pseudonym))
			return Optional.empty();

		return Optional.of(watermark.minus(watermarkBuffer));
	}

	public Bundle createFrom(List<Observation> observations, String pseudonym)
	{
		Objects.requireNonNull(observations, "observations");
		Objects.requireNonNull(pseudonym, "pseudonym");

		Bundle bundle = new Bundle();
		bundle.setType(Bundle.BundleType.COLLECTION);

		observations.stream().filter(this::isSelected).map(observation -> minimize(observation, pseudonym))
				.forEach(observation -> bundle.addEntry().setResource(observation));

		return bundle;
	}

	private boolean isSelected(Observation observation)
	{
		return Observation.ObservationStatus.FINAL.equals(observation.getStatus())
				&& hasConfiguredLoincCode(observation);
	}

	private boolean hasConfiguredLoincCode(Observation observation)
	{
		return observation.getCode().getCoding().stream()
				.anyMatch(c -> LOINC_SYSTEM.equals(c.getSystem()) && loincCodes.contains(c.getCode()));
	}

	private Observation minimize(Observation source, String pseudonym)
	{
		Observation target = new Observation();

		target.setId(source.getIdElement().getIdPart());
		target.setIdentifier(source.getIdentifier().stream().map(i -> i.copy()).toList());
		target.setStatus(source.getStatus());
		target.setCategory(source.getCategory().stream().map(c -> c.copy()).toList());
		target.setCode(source.getCode().copy());
		target.setSubject(new Reference("Patient/" + pseudonym));

		if (source.hasEffective())
			target.setEffective(source.getEffective().copy());
		if (source.hasValue())
			target.setValue(source.getValue().copy());

		target.setComponent(source.getComponent().stream().map(c -> c.copy()).toList());

		return target;
	}
}
