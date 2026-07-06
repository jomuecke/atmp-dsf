package de.ukhd.process.atmp.variables;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class SeenSubjectsTest
{
	private static final String PSEUDONYM = "ATMP-0001";

	@Test
	public void testMarkAddsPseudonymWhenAbsent()
	{
		List<String> seen = SeenSubjects.mark(List.of("ATMP-0002"), PSEUDONYM);

		assertEquals(List.of("ATMP-0002", PSEUDONYM), seen);
	}

	@Test
	public void testMarkIsIdempotent()
	{
		List<String> seen = SeenSubjects.mark(List.of(PSEUDONYM), PSEUDONYM);

		assertEquals(List.of(PSEUDONYM), seen);
	}

	@Test
	public void testMarkNullSeenTreatedAsEmpty()
	{
		assertEquals(List.of(PSEUDONYM), SeenSubjects.mark(null, PSEUDONYM));
	}

	@Test
	public void testUnmarkRemovesPseudonymSoStateIsNotAdvanced()
	{
		// a failed subject is unmarked -> next cycle bulk-on-first-sight re-queries it in full
		List<String> seen = SeenSubjects.unmark(List.of("ATMP-0002", PSEUDONYM), PSEUDONYM);

		assertEquals(List.of("ATMP-0002"), seen);
		assertFalse(seen.contains(PSEUDONYM));
	}

	@Test
	public void testUnmarkAbsentPseudonymIsNoOp()
	{
		assertEquals(List.of("ATMP-0002"), SeenSubjects.unmark(List.of("ATMP-0002"), PSEUDONYM));
	}

	@Test
	public void testUnmarkNullSeenTreatedAsEmpty()
	{
		assertTrue(SeenSubjects.unmark(null, PSEUDONYM).isEmpty());
	}

	@Test
	public void testBadSubjectInBatchLeavesOthersIntact()
	{
		// Models a cycle over subjects A (ok), B (fails), C (ok): each subject is handled independently, so B's failure
		// (unmark) neither advances B nor disturbs A and C (which are marked seen). B stays unseen -> retried in full.
		List<String> seen = List.of();
		seen = SeenSubjects.mark(seen, "A");
		seen = SeenSubjects.unmark(seen, "B");
		seen = SeenSubjects.mark(seen, "C");

		assertEquals(List.of("A", "C"), seen);
		assertFalse(seen.contains("B"));
	}
}
