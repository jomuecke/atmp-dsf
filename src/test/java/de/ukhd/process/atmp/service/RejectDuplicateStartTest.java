package de.ukhd.process.atmp.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.List;

import org.hl7.fhir.r4.model.Task;
import org.junit.Test;

public class RejectDuplicateStartTest
{
	private final RejectDuplicateStart guard = new RejectDuplicateStart();

	private Task task(String id, long authoredEpochMilli)
	{
		Task task = new Task();
		task.setId("Task/" + id);
		task.setAuthoredOn(new Date(authoredEpochMilli));
		return task;
	}

	@Test
	public void testAllowsSingleActiveInstance()
	{
		assertFalse(guard.isDuplicateStart(task("a", 1000), List.of()));
	}

	@Test
	public void testRejectsWhenOlderTaskInProgress()
	{
		assertTrue(guard.isDuplicateStart(task("b", 2000), List.of(task("a", 1000))));
	}

	@Test
	public void testAllowsWhenOnlyYoungerTaskInProgress()
	{
		assertFalse(guard.isDuplicateStart(task("a", 1000), List.of(task("b", 2000))));
	}

	@Test
	public void testSameAuthoredExactlyOneSurvives()
	{
		Task a = task("a", 1000), b = task("b", 1000);
		boolean aRejected = guard.isDuplicateStart(a, List.of(b));
		boolean bRejected = guard.isDuplicateStart(b, List.of(a));
		assertTrue("exactly one of two simultaneous starts must be rejected", aRejected ^ bRejected);
	}

	@Test
	public void testOtherWithoutAuthoredWins()
	{
		Task withoutAuthored = new Task();
		withoutAuthored.setId("Task/a");
		assertTrue(guard.isDuplicateStart(task("b", 1000), List.of(withoutAuthored)));
	}
}
