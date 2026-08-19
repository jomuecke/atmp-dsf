package de.ukhd.process.atmp.variables;

import java.util.Objects;

import de.ukhd.process.atmp.ConstantsAtmp;

/**
 * A single entry of the {@code atmpResearchSubjects} multi-instance collection, encoding a study participant as
 * {@code <patient-reference>|<pseudonym>} (see {@link ConstantsAtmp#RESEARCH_SUBJECT_ENTRY_SEPARATOR}).
 */
public record SubjectEntry(String patientReference, String pseudonym)
{
	public static SubjectEntry parse(String entry)
	{
		Objects.requireNonNull(entry, "entry");

		int separator = entry.lastIndexOf(ConstantsAtmp.RESEARCH_SUBJECT_ENTRY_SEPARATOR);
		if (separator < 0)
			throw new IllegalArgumentException("Malformed research subject entry '" + entry + "'");

		return new SubjectEntry(entry.substring(0, separator), entry.substring(separator + 1));
	}
}
