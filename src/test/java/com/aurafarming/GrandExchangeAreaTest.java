package com.aurafarming;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GrandExchangeAreaTest
{
	// A small, deterministic area independent of the production constants, so this test
	// does not silently pass/fail if the real GE bounds are later corrected.
	private static final int SW_X = 100;
	private static final int SW_Y = 200;
	private static final int WIDTH = 10;
	private static final int HEIGHT = 10;
	private static final int PLANE = 0;

	private GrandExchangeArea area;

	@Before
	public void setUp()
	{
		area = new GrandExchangeArea(new WorldArea(SW_X, SW_Y, WIDTH, HEIGHT, PLANE));
	}

	@Test
	public void centerTileIsInside()
	{
		WorldPoint center = new WorldPoint(SW_X + WIDTH / 2, SW_Y + HEIGHT / 2, PLANE);
		assertTrue(area.contains(center));
	}

	@Test
	public void southWestCornerIsInside()
	{
		WorldPoint corner = new WorldPoint(SW_X, SW_Y, PLANE);
		assertTrue(area.contains(corner));
	}

	@Test
	public void northEastCornerIsInside()
	{
		WorldPoint corner = new WorldPoint(SW_X + WIDTH - 1, SW_Y + HEIGHT - 1, PLANE);
		assertTrue(area.contains(corner));
	}

	@Test
	public void tileImmediatelyOutsideWestEdgeIsOutside()
	{
		WorldPoint outside = new WorldPoint(SW_X - 1, SW_Y, PLANE);
		assertFalse(area.contains(outside));
	}

	@Test
	public void tileImmediatelyOutsideEastEdgeIsOutside()
	{
		WorldPoint outside = new WorldPoint(SW_X + WIDTH, SW_Y, PLANE);
		assertFalse(area.contains(outside));
	}

	@Test
	public void sameXyDifferentPlaneIsOutside()
	{
		WorldPoint upstairs = new WorldPoint(SW_X + 1, SW_Y + 1, PLANE + 1);
		assertFalse(area.contains(upstairs));
	}

	@Test
	public void nullPointIsOutside()
	{
		assertFalse(area.contains(null));
	}
}
