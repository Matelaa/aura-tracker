package com.aurafarming;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AuraScoreCalculatorTest
{
	private AuraScoreCalculator calculator;

	@Before
	public void setUp()
	{
		calculator = new AuraScoreCalculator();
	}

	@Test
	public void zeroSecondsIsZeroPoints()
	{
		assertEquals(0L, calculator.toAuraPoints(0));
	}

	@Test
	public void fiftyNineSecondsIsZeroPoints()
	{
		assertEquals(0L, calculator.toAuraPoints(59));
	}

	@Test
	public void sixtySecondsIsOnePoint()
	{
		assertEquals(1L, calculator.toAuraPoints(60));
	}

	@Test
	public void severalMinutesRoundsDown()
	{
		assertEquals(2L, calculator.toAuraPoints(125));
	}

	@Test
	public void largePeriodComputesCorrectly()
	{
		// 8 days, 17 hours = 752 400 seconds -> 12 540 points
		long eightDaysSeventeenHours = (8L * 86400) + (17L * 3600);
		assertEquals(12540L, calculator.toAuraPoints(eightDaysSeventeenHours));
	}

	@Test
	public void negativeSecondsIsZeroPoints()
	{
		assertEquals(0L, calculator.toAuraPoints(-100));
	}
}
