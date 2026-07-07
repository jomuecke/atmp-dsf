package de.ukhd.process.atmp.service;

import org.junit.Test;

public class RejectDuplicateStartTest
{
	private final RejectDuplicateStart guard = new RejectDuplicateStart(null);

	@Test
	public void testAllowsSingleActiveInstance()
	{
		org.junit.Assert.assertFalse(guard.isDuplicateStart(1));
	}

	@Test
	public void testRejectsDuplicateActiveInstance()
	{
		org.junit.Assert.assertTrue(guard.isDuplicateStart(2));
	}
}
