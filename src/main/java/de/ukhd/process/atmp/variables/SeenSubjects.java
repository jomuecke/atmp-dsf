package de.ukhd.process.atmp.variables;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure operations on the {@code atmpSeenSubjects} set (bulk-on-first-sight state, see Issue&nbsp;C). A subject in the
 * set has already been handled in this instance and is queried incrementally; a subject not in the set is queried in
 * full.
 *
 * <p>
 * Error-handling (Issue&nbsp;D) uses {@link #unmark(List, String)} to <em>not advance</em> a failed subject's state:
 * the subject is left out of / removed from the set so the next cycle re-queries it in full and retries the whole
 * subject (MEDIC absorbs any harmless re-send via upsert-by-{@code id}).
 */
public final class SeenSubjects
{
	private SeenSubjects()
	{
	}

	/**
	 * @return a new list containing {@code pseudonym} exactly once (appended if absent), preserving existing order.
	 */
	public static List<String> mark(List<String> seen, String pseudonym)
	{
		Objects.requireNonNull(pseudonym, "pseudonym");

		List<String> result = new ArrayList<>(seen == null ? List.of() : seen);
		if (!result.contains(pseudonym))
			result.add(pseudonym);

		return result;
	}

	/**
	 * @return a new list with every occurrence of {@code pseudonym} removed, preserving the order of the rest.
	 */
	public static List<String> unmark(List<String> seen, String pseudonym)
	{
		Objects.requireNonNull(pseudonym, "pseudonym");

		List<String> result = new ArrayList<>(seen == null ? List.of() : seen);
		result.removeIf(pseudonym::equals);

		return result;
	}
}
