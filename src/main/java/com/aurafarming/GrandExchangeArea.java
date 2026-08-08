package com.aurafarming;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * Defines the Grand Exchange courtyard as a testable, isolated area check.
 * <p>
 * Bounds validated in-game via the RuneLite Developer Tools' Location overlay (2026-08-07):
 * SW corner {@code World 3160, 3481, 0}, NE corner {@code World 3169, 3498, 0}, both walked
 * to and read directly off the fenced courtyard, not estimated.
 */
public final class GrandExchangeArea
{
	private static final int SOUTH_WEST_X = 3160;
	private static final int SOUTH_WEST_Y = 3481;
	private static final int WIDTH = 9;
	private static final int HEIGHT = 17;
	private static final int PLANE = 0;

	private final WorldArea fencedArea;

	public GrandExchangeArea()
	{
		this.fencedArea = new WorldArea(SOUTH_WEST_X, SOUTH_WEST_Y, WIDTH, HEIGHT, PLANE);
	}

	/**
	 * Package-private constructor used by tests to inject custom bounds without touching
	 * the production constants.
	 */
	GrandExchangeArea(WorldArea fencedArea)
	{
		this.fencedArea = fencedArea;
	}

	/**
	 * @param point the local player's own world location. Never called with another
	 *              player's location — this plugin observes only the local player.
	 * @return true if the point falls inside the fenced Grand Exchange courtyard.
	 */
	public boolean contains(WorldPoint point)
	{
		if (point == null)
		{
			return false;
		}

		return fencedArea.contains(point);
	}
}
