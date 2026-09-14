package com.aurafarming;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AuraSessionTest
{
	private AuraSession session;

	@Before
	public void setUp()
	{
		session = new AuraSession();
	}

	@Test
	public void zeroAuraSyncStreakStartsAtZero()
	{
		assertEquals(0, session.getZeroAuraSyncStreak());
	}

	@Test
	public void recordZeroAuraSyncIncrementsTheStreak()
	{
		session.recordZeroAuraSync();
		assertEquals(1, session.getZeroAuraSyncStreak());

		session.recordZeroAuraSync();
		session.recordZeroAuraSync();
		assertEquals(3, session.getZeroAuraSyncStreak());
	}

	@Test
	public void recordZeroAuraSyncKeepsCountingPastAnyRealisticThreshold()
	{
		for (int i = 0; i < 100; i++)
		{
			session.recordZeroAuraSync();
		}
		assertEquals(100, session.getZeroAuraSyncStreak());
	}

	@Test
	public void earningAuraDoesNotByItselfResetTheStreak()
	{
		// AuraPlugin only calls recordZeroAuraSync() while the total is still zero, so
		// in practice the streak simply stops being incremented once Aura is earned —
		// AuraSession itself has no reset logic, and none is needed (see the field's
		// own doc comment: once positive, the all-time total never returns to zero).
		session.recordZeroAuraSync();
		session.recordZeroAuraSync();
		session.addEligibleSeconds(60);

		assertEquals(2, session.getZeroAuraSyncStreak());
		assertEquals(60L, session.getEligibleAuraDurationSeconds());
	}
}
