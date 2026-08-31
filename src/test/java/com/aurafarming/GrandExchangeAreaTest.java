package com.aurafarming;

import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GrandExchangeAreaTest
{
	// A small, deterministic octagon independent of the production constants, so this
	// test does not silently pass/fail if the real GE bounds are later corrected. A
	// 20x20 square with each corner cut back by 4 tiles on the diagonal — the same
	// shape as the real courtyard, just at a size that's easy to reason about by hand.
	private static final int MIN_X = 100;
	private static final int MAX_X = 119;
	private static final int MIN_Y = 200;
	private static final int MAX_Y = 219;
	private static final int CORNER_CUT = 4;
	private static final int MIN_SUM = MIN_X + MIN_Y + CORNER_CUT;
	private static final int MAX_SUM = MAX_X + MAX_Y - CORNER_CUT;
	private static final int MIN_DIFF = MIN_X - MAX_Y + CORNER_CUT;
	private static final int MAX_DIFF = MAX_X - MIN_Y - CORNER_CUT;
	private static final int PLANE = 0;

	private GrandExchangeArea area;

	@Before
	public void setUp()
	{
		area = new GrandExchangeArea(MIN_X, MAX_X, MIN_Y, MAX_Y, MIN_SUM, MAX_SUM, MIN_DIFF, MAX_DIFF, PLANE);
	}

	@Test
	public void centerTileIsInside()
	{
		WorldPoint center = new WorldPoint((MIN_X + MAX_X) / 2, (MIN_Y + MAX_Y) / 2, PLANE);
		assertTrue(area.contains(center));
	}

	@Test
	public void midpointOfEachFlatEdgeIsInside()
	{
		int midX = (MIN_X + MAX_X) / 2;
		int midY = (MIN_Y + MAX_Y) / 2;

		// This is the exact regression a plain bounding-box check got wrong: real GE
		// tiles along the west/east walls, away from the corners, must count as inside.
		assertTrue(area.contains(new WorldPoint(MIN_X, midY, PLANE))); // west
		assertTrue(area.contains(new WorldPoint(MAX_X, midY, PLANE))); // east
		assertTrue(area.contains(new WorldPoint(midX, MAX_Y, PLANE))); // north
		assertTrue(area.contains(new WorldPoint(midX, MIN_Y, PLANE))); // south
	}

	@Test
	public void squareCornersAreOutsideTheOctagonCut()
	{
		// The four corners of the bounding square are exactly what the diagonal cut
		// removes — the whole reason this class stopped being a plain rectangle.
		assertFalse(area.contains(new WorldPoint(MIN_X, MIN_Y, PLANE))); // SW
		assertFalse(area.contains(new WorldPoint(MAX_X, MIN_Y, PLANE))); // SE
		assertFalse(area.contains(new WorldPoint(MIN_X, MAX_Y, PLANE))); // NW
		assertFalse(area.contains(new WorldPoint(MAX_X, MAX_Y, PLANE))); // NE
	}

	@Test
	public void tileImmediatelyOutsideWestEdgeIsOutside()
	{
		int midY = (MIN_Y + MAX_Y) / 2;
		WorldPoint outside = new WorldPoint(MIN_X - 1, midY, PLANE);
		assertFalse(area.contains(outside));
	}

	@Test
	public void tileImmediatelyOutsideEastEdgeIsOutside()
	{
		int midY = (MIN_Y + MAX_Y) / 2;
		WorldPoint outside = new WorldPoint(MAX_X + 1, midY, PLANE);
		assertFalse(area.contains(outside));
	}

	@Test
	public void sameXyDifferentPlaneIsOutside()
	{
		WorldPoint upstairs = new WorldPoint((MIN_X + MAX_X) / 2, (MIN_Y + MAX_Y) / 2, PLANE + 1);
		assertFalse(area.contains(upstairs));
	}

	@Test
	public void nullPointIsOutside()
	{
		assertFalse(area.contains(null));
	}
}
