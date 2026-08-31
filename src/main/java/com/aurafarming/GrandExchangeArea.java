package com.aurafarming;

import net.runelite.api.coords.WorldPoint;

/**
 * Defines the Grand Exchange courtyard as a testable, isolated area check.
 * <p>
 * The courtyard is an octagon — a square with its four corners cut off on the
 * diagonal — not a rectangle. An earlier bounding-box version of this class
 * (validated 2026-08-07) missed real GE tiles near the west and east walls: a plain
 * rectangle narrow enough to exclude the corners also excludes valid tiles along the
 * flat west/east edges, since those edges sit wider than the rectangle's corners.
 * <p>
 * Modeled here as the intersection of two axis-aligned ranges ({@code x}, {@code y})
 * and two diagonal ranges ({@code x+y}, {@code x-y}) — eight bounds in total — so the
 * check stays a handful of integer comparisons, no polygon math or library needed.
 * All eight bounds validated in-game via the RuneLite Developer Tools' Location
 * overlay: the westmost/eastmost/northmost/southmost standable tile for the
 * axis-aligned ranges, and all four diagonal corners (independently, to catch any
 * real asymmetry rather than assume it) for the diagonal ranges (2026-08-31).
 */
public final class GrandExchangeArea
{
	private static final int MIN_X = 3155;
	private static final int MAX_X = 3174;
	private static final int MIN_Y = 3480;
	private static final int MAX_Y = 3499;

	// x+y and x-y bounds, one pair per diagonal corner — these are what cut the square
	// above into an octagon. Each corner constrains a different one of the four:
	// NE caps x+y, SW floors x+y, NW floors x-y, SE caps x-y.
	private static final int MIN_SUM = 6641; // x + y, from the SW corner (3158, 3483)
	private static final int MAX_SUM = 6667; // x + y, from the NE corner (3171, 3496)
	private static final int MIN_DIFF = -338; // x - y, from the NW corner (3158, 3496)
	private static final int MAX_DIFF = -312; // x - y, from the SE corner (3171, 3483)

	private static final int PLANE = 0;

	private final int minX;
	private final int maxX;
	private final int minY;
	private final int maxY;
	private final int minSum;
	private final int maxSum;
	private final int minDiff;
	private final int maxDiff;
	private final int plane;

	public GrandExchangeArea()
	{
		this(MIN_X, MAX_X, MIN_Y, MAX_Y, MIN_SUM, MAX_SUM, MIN_DIFF, MAX_DIFF, PLANE);
	}

	/**
	 * Package-private constructor used by tests to inject custom bounds without
	 * touching the production constants.
	 */
	GrandExchangeArea(int minX, int maxX, int minY, int maxY, int minSum, int maxSum, int minDiff, int maxDiff,
						int plane)
	{
		this.minX = minX;
		this.maxX = maxX;
		this.minY = minY;
		this.maxY = maxY;
		this.minSum = minSum;
		this.maxSum = maxSum;
		this.minDiff = minDiff;
		this.maxDiff = maxDiff;
		this.plane = plane;
	}

	/**
	 * @param point the local player's own world location. Never called with another
	 *              player's location — this plugin observes only the local player.
	 * @return true if the point falls inside the octagonal Grand Exchange courtyard.
	 */
	public boolean contains(WorldPoint point)
	{
		if (point == null || point.getPlane() != plane)
		{
			return false;
		}

		int x = point.getX();
		int y = point.getY();
		int sum = x + y;
		int diff = x - y;

		return x >= minX && x <= maxX
			&& y >= minY && y <= maxY
			&& sum >= minSum && sum <= maxSum
			&& diff >= minDiff && diff <= maxDiff;
	}
}
