package de.ukhd.process.atmp.fhir;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

import org.hl7.fhir.r4.model.BaseDateTimeType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;

/**
 * Pure domain core of the ATMP data transfer: selects the laboratory {@link Observation}s to send (configured LOINC
 * codes, status {@code final}) and transforms them into one pseudonymized FHIR {@code collection} {@link Bundle} per
 * study participant.
 *
 * <p>
 * Per Observation: {@code subject.reference} is rewritten to {@code Patient/<pseudonym>}, the source
 * {@code Observation.id} is preserved unchanged (the register upserts by id), the local {@code Observation.identifier}
 * is kept, and all other fields are minimized to the agreed set: id, meta, status, category, code, subject,
 * effective[x], value[x] and component.
 *
 * <p>
 * The shape produced here is constrained by the register's import API (see {@code medic-import.yaml}), which is
 * stricter than FHIR R4 itself. The bundle carries an {@code id} and {@code meta.lastUpdated}; every entry must have
 * {@code meta.versionId}/{@code lastUpdated}/{@code source} (forwarded unchanged from the local store), an
 * {@code effectiveDateTime} with second precision and an explicit numeric UTC offset (a bare {@code Z} is rejected by
 * the register), and a complete {@code valueQuantity}. Observations that cannot satisfy this are <b>not</b> silently
 * dropped: they are reported as {@link RejectedObservation}s in the {@link SubjectBundle} so the caller can audit them.
 * {@code meta.profile}/{@code tag}/{@code security} are dropped as part of the minimization.
 */
public class ObservationBundleFactory
{
	/**
	 * An Observation that was selected for transfer but left out of the bundle because it cannot satisfy the register's
	 * import schema. Reported so the omission is auditable instead of silent.
	 *
	 * @param observationId
	 *            logical id of the source Observation, may be <code>null</code> when that is what is missing
	 * @param reason
	 *            why the register would reject it, never <code>null</code>
	 */
	public record RejectedObservation(String observationId, String reason)
	{
		@Override
		public String toString()
		{
			return (observationId == null || observationId.isBlank() ? "<no id>" : observationId) + ": " + reason;
		}
	}

	/**
	 * The bundle to send for one subject, plus the Observations left out of it because the register would reject them.
	 */
	public record SubjectBundle(Bundle bundle, List<RejectedObservation> rejected)
	{
		public SubjectBundle
		{
			Objects.requireNonNull(bundle, "bundle");
			rejected = List.copyOf(rejected);
		}
	}

	public static final String LOINC_SYSTEM = "http://loinc.org";

	/**
	 * The register validates {@code subject.reference} against {@code ^Patient/[\w\d-]{0,30}$}; a pseudonym outside
	 * this shape fails every entry of the subject's bundle.
	 */
	public static final Pattern PSEUDONYM_PATTERN = Pattern.compile("[\\w-]{0,30}");

	/**
	 * The register requires second precision and an explicit <b>positive</b> numeric UTC offset on every date-time; a
	 * bare {@code Z}/Zulu suffix is rejected.
	 */
	private static final Pattern OFFSET_DATE_TIME_PATTERN = Pattern
			.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?\\+\\d{2}:\\d{2}");

	private final Set<String> loincCodes;
	private final Duration watermarkBuffer;
	private final Clock clock;

	public ObservationBundleFactory(Collection<String> loincCodes)
	{
		this(loincCodes, Duration.ZERO);
	}

	public ObservationBundleFactory(Collection<String> loincCodes, Duration watermarkBuffer)
	{
		this(loincCodes, watermarkBuffer, Clock.systemDefaultZone());
	}

	/**
	 * @param clock
	 *            supplies the bundle's {@code meta.lastUpdated} and, through its zone, the UTC offset all date-times
	 *            are rewritten to (the register rejects {@code Z}), not <code>null</code>
	 */
	public ObservationBundleFactory(Collection<String> loincCodes, Duration watermarkBuffer, Clock clock)
	{
		Objects.requireNonNull(loincCodes, "loincCodes");
		Objects.requireNonNull(watermarkBuffer, "watermarkBuffer");
		Objects.requireNonNull(clock, "clock");
		this.loincCodes = Set.copyOf(loincCodes);
		this.watermarkBuffer = watermarkBuffer;
		this.clock = clock;
	}

	/**
	 * Decides the {@code Observation._lastUpdated} lower bound for querying a single subject's laboratory results:
	 * {@link Optional#empty()} means a <b>full</b> (un-watermarked) query — everything for the subject — while a
	 * present value means an <b>incremental</b> query for {@code _lastUpdated} greater than that instant.
	 *
	 * <p>
	 * A full query is used when no cycle has completed yet (no watermark) or the first time a subject is seen in this
	 * instance (bulk-on-first-sight, covers late enrollment). Otherwise the incremental bound is the watermark minus
	 * the configured buffer, which absorbs BPE&harr;FHIR-store clock skew; any resulting re-sends are harmless because
	 * the register upserts by {@code Observation.id}. A full re-send of everything is forced by stopping and
	 * re-starting the process: a fresh instance has neither watermark nor seen subjects, so its first cycle is always
	 * full.
	 *
	 * @param pseudonym
	 *            the subject's ATMP pseudonym, not <code>null</code>
	 * @param watermark
	 *            start instant of the last completed cycle, or <code>null</code> if none completed yet
	 * @param seenSubjects
	 *            pseudonyms already handled in this instance, not <code>null</code>
	 * @return the incremental lower bound, or {@link Optional#empty()} for a full query
	 */
	public Optional<Instant> queryLowerBound(String pseudonym, Instant watermark, Collection<String> seenSubjects)
	{
		Objects.requireNonNull(pseudonym, "pseudonym");
		Objects.requireNonNull(seenSubjects, "seenSubjects");

		if (watermark == null || !seenSubjects.contains(pseudonym))
			return Optional.empty();

		return Optional.of(watermark.minus(watermarkBuffer));
	}

	/**
	 * @throws IllegalArgumentException
	 *             if {@code pseudonym} does not match {@link #PSEUDONYM_PATTERN} — the register would reject every
	 *             entry of this subject's bundle, so failing the subject as a whole is more honest than sending it
	 */
	public SubjectBundle createFrom(List<Observation> observations, String pseudonym)
	{
		Objects.requireNonNull(observations, "observations");
		Objects.requireNonNull(pseudonym, "pseudonym");

		if (!PSEUDONYM_PATTERN.matcher(pseudonym).matches())
			throw new IllegalArgumentException("Pseudonym does not match the register's subject reference pattern "
					+ PSEUDONYM_PATTERN.pattern() + ", would be rejected for every Observation of this subject");

		Instant now = clock.instant();

		Bundle bundle = new Bundle();
		bundle.setType(Bundle.BundleType.COLLECTION);
		bundle.setId("Bundle" + now.toEpochMilli());
		bundle.getMeta().setLastUpdatedElement(offsetInstant(now));

		List<RejectedObservation> rejected = new ArrayList<>();

		for (Observation observation : observations)
		{
			if (!isSelected(observation))
				continue;

			Observation minimized = minimize(observation, pseudonym);
			Optional<String> issue = registerSchemaIssue(minimized);

			if (issue.isPresent())
				rejected.add(new RejectedObservation(observation.getIdElement().getIdPart(), issue.get()));
			else
				bundle.addEntry().setResource(minimized);
		}

		return new SubjectBundle(bundle, rejected);
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
		target.setMeta(minimizeMeta(source.getMeta()));
		target.setIdentifier(source.getIdentifier().stream().map(i -> i.copy()).toList());
		target.setStatus(source.getStatus());
		target.setCategory(source.getCategory().stream().map(c -> c.copy()).toList());
		target.setCode(source.getCode().copy());
		target.setSubject(new Reference("Patient/" + pseudonym));

		if (source.hasEffectiveDateTimeType())
			target.setEffective(withRegisterOffset(source.getEffectiveDateTimeType()));
		else if (source.hasEffective())
			// kept as-is so registerSchemaIssue can name the actual type in its rejection reason
			target.setEffective(source.getEffective().copy());

		if (source.hasValue())
			target.setValue(source.getValue().copy());

		target.setComponent(source.getComponent().stream().map(c -> c.copy()).toList());

		return target;
	}

	/**
	 * Keeps only the three {@code meta} fields the register requires — {@code versionId}, {@code lastUpdated} and
	 * {@code source} — forwarded unchanged from the local store apart from the date-time offset rewrite. Local
	 * {@code profile}, {@code tag} and {@code security} are dropped.
	 */
	private Meta minimizeMeta(Meta source)
	{
		Meta target = new Meta();

		if (source.hasVersionId())
			target.setVersionId(source.getVersionId());
		if (source.hasLastUpdated())
			target.setLastUpdatedElement(withRegisterOffset(source.getLastUpdatedElement()));
		if (source.hasSource())
			target.setSource(source.getSource());

		return target;
	}

	private InstantType offsetInstant(Instant instant)
	{
		return new InstantType(Date.from(instant), TemporalPrecisionEnum.MILLI, timeZone());
	}

	/**
	 * Rewrites a date-time to the factory clock's zone so it carries an explicit numeric UTC offset instead of a bare
	 * {@code Z}, preserving the instant. Values without at least second precision are returned unchanged and rejected
	 * downstream — inventing a time-of-day for a date-only value would fabricate data.
	 */
	@SuppressWarnings("unchecked")
	private <T extends BaseDateTimeType> T withRegisterOffset(T source)
	{
		if (source.getValue() == null || source.getPrecision().ordinal() < TemporalPrecisionEnum.SECOND.ordinal())
			return (T) source.copy();

		TemporalPrecisionEnum precision = TemporalPrecisionEnum.MILLI.equals(source.getPrecision())
				? TemporalPrecisionEnum.MILLI
				: TemporalPrecisionEnum.SECOND;

		return (T) (source instanceof InstantType ? new InstantType(source.getValue(), precision, timeZone())
				: new DateTimeType(source.getValue(), precision, timeZone()));
	}

	private TimeZone timeZone()
	{
		return TimeZone.getTimeZone(clock.getZone());
	}

	/**
	 * @return why the register's import schema would reject {@code observation}, or {@link Optional#empty()} if it
	 *         satisfies it
	 */
	private Optional<String> registerSchemaIssue(Observation observation)
	{
		String id = observation.getIdElement().getIdPart();
		if (id == null || id.isBlank())
			return Optional.of("missing Observation.id");

		Meta meta = observation.getMeta();
		if (!meta.hasVersionId())
			return Optional.of("missing meta.versionId (required by the register, not set by the local FHIR store)");
		if (!meta.hasLastUpdated())
			return Optional.of("missing meta.lastUpdated (required by the register)");
		if (!isOffsetDateTime(meta.getLastUpdatedElement()))
			return Optional.of("meta.lastUpdated '" + meta.getLastUpdatedElement().getValueAsString()
					+ "' is not a second-precision date-time with a positive numeric UTC offset");
		if (!meta.hasSource())
			return Optional.of("missing meta.source (required by the register, not set by the local FHIR store)");

		if (!observation.hasCode() || observation.getCode().getCoding().isEmpty()
				|| !observation.getCode().getCodingFirstRep().hasSystem()
				|| !observation.getCode().getCodingFirstRep().hasCode())
			return Optional.of("code.coding[0] must have both system and code");

		if (!observation.hasEffectiveDateTimeType())
			return Optional.of("effective[x] must be an effectiveDateTime, but is "
					+ (observation.hasEffective() ? observation.getEffective().fhirType() : "absent"));
		if (!isOffsetDateTime(observation.getEffectiveDateTimeType()))
			return Optional.of("effectiveDateTime '" + observation.getEffectiveDateTimeType().getValueAsString()
					+ "' is not a second-precision date-time with a positive numeric UTC offset");

		if (!observation.hasValueQuantity())
			return Optional.of("value[x] must be a valueQuantity, but is "
					+ (observation.hasValue() ? observation.getValue().fhirType() : "absent"));

		Quantity value = observation.getValueQuantity();
		if (!value.hasValue() || !value.hasUnit() || !value.hasSystem() || !value.hasCode())
			return Optional.of("valueQuantity must have all of value, unit, system and code");

		return Optional.empty();
	}

	private boolean isOffsetDateTime(BaseDateTimeType dateTime)
	{
		String value = dateTime.getValueAsString();
		return value != null && OFFSET_DATE_TIME_PATTERN.matcher(value).matches();
	}
}
