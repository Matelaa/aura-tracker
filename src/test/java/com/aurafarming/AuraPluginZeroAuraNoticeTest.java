package com.aurafarming;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers only {@link AuraPlugin#shouldShowZeroAuraNotice}, the pure decision logic
 * pulled out of {@code maybeShowZeroAuraNotice} specifically so it doesn't need a
 * mocked {@code Client}/{@code ConfigManager}/{@code ClientThread} — see that
 * method's own doc comment. The "already shown this session" and the actual chat
 * message / config write are deliberately untested here: they're the same kind of
 * RuneLite-glue code the rest of {@link AuraPlugin} already has no direct test
 * coverage for.
 */
public class AuraPluginZeroAuraNoticeTest
{
	private static final int THRESHOLD = 5;

	@Test
	public void staysHiddenBelowTheThresholdOnAFreshInstall()
	{
		assertFalse(AuraPlugin.shouldShowZeroAuraNotice(false, 0, THRESHOLD));
		assertFalse(AuraPlugin.shouldShowZeroAuraNotice(false, THRESHOLD - 1, THRESHOLD));
	}

	@Test
	public void showsOnceTheStreakReachesTheThreshold()
	{
		assertTrue(AuraPlugin.shouldShowZeroAuraNotice(false, THRESHOLD, THRESHOLD));
		assertTrue(AuraPlugin.shouldShowZeroAuraNotice(false, THRESHOLD + 50, THRESHOLD));
	}

	@Test
	public void showsImmediatelyOnceTheThresholdWasAlreadyClearedInAnEarlierSession()
	{
		// A later session's streak resets to whatever this sync tick's local count is
		// (it's never reset in AuraSession, but a fresh session could still start
		// mid-streak at a low number) — the persisted "cleared" flag must be enough on
		// its own, independent of the current streak value.
		assertTrue(AuraPlugin.shouldShowZeroAuraNotice(true, 0, THRESHOLD));
		assertTrue(AuraPlugin.shouldShowZeroAuraNotice(true, 1, THRESHOLD));
	}
}
