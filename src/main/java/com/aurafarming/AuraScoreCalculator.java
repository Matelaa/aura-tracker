package com.aurafarming;

/**
 * Converts eligible duration into an Aura score.
 * <p>
 * This is the ONLY place the Aura formula is allowed to live. Nothing else in the
 * plugin — tracker, panel, or repository — should hardcode the conversion. This keeps
 * the formula changeable later without touching tracking or persistence logic.
 * <p>
 * MVP formula: 1 Aura point per minute of eligible time, rounded down.
 */
public class AuraScoreCalculator
{
	private static final long SECONDS_PER_AURA_POINT = 60L;

	/**
	 * @param eligibleSeconds total eligible seconds standing still in the Grand Exchange.
	 *                        Must be non-negative; negative input is treated as zero.
	 * @return the Aura score, rounded down to whole points.
	 */
	public long toAuraPoints(long eligibleSeconds)
	{
		if (eligibleSeconds <= 0)
		{
			return 0L;
		}

		return eligibleSeconds / SECONDS_PER_AURA_POINT;
	}
}
