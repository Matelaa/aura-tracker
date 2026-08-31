package com.aurafarming;

import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AuraSessionTrackerTest
{
	private static final int SW_X = 100;
	private static final int SW_Y = 200;
	private static final int PLANE = 0;

	private static final WorldPoint INSIDE_GE = new WorldPoint(SW_X + 2, SW_Y + 2, PLANE);
	private static final WorldPoint INSIDE_GE_DIFFERENT_TILE = new WorldPoint(SW_X + 3, SW_Y + 2, PLANE);
	private static final WorldPoint OUTSIDE_GE = new WorldPoint(SW_X - 50, SW_Y, PLANE);

	private static final int IDLE_DELAY_SECONDS = 5;

	private FakeClock fakeClock;
	private AuraSessionTracker tracker;
	private AuraSession session;

	@Before
	public void setUp()
	{
		GrandExchangeArea area = plainSquareArea();
		fakeClock = new FakeClock(0L);
		session = new AuraSession();
		tracker = new AuraSessionTracker(area, new AuraTimingClock(fakeClock), session, fakeClock);

		tracker.onGameStateChanged(GameState.LOGGED_IN);
	}

	@Test
	public void startsAsWaitingForIdleUponEnteringGe()
	{
		tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);
		assertEquals(AuraState.WAITING_FOR_IDLE, tracker.getState());
	}

	@Test
	public void doesNotStartEarningBeforeIdleDelayElapses()
	{
		tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);
		fakeClock.advanceSeconds(3);
		tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);

		assertEquals(AuraState.WAITING_FOR_IDLE, tracker.getState());
		assertEquals(0L, session.getCurrentSessionEligibleSeconds());
	}

	@Test
	public void startsEarningOnceIdleDelayElapses()
	{
		tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);
		fakeClock.advanceSeconds(IDLE_DELAY_SECONDS);
		tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);

		assertEquals(AuraState.EARNING_AURA, tracker.getState());
	}

	@Test
	public void movingToAnotherTileResetsIdleWait()
	{
		tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);
		fakeClock.advanceSeconds(IDLE_DELAY_SECONDS);
		tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);
		assertEquals(AuraState.EARNING_AURA, tracker.getState());

		tracker.onGameTick(INSIDE_GE_DIFFERENT_TILE, IDLE_DELAY_SECONDS);
		assertEquals(AuraState.WAITING_FOR_IDLE, tracker.getState());
	}

	@Test
	public void leavingGePausesTrackingButKeepsAccumulatedTotal()
	{
		enterGeAndStartEarning();
		accumulateOneRealisticTick(); // 1 whole second accumulated

		long accumulatedBeforeLeaving = session.getEligibleAuraDurationSeconds();
		assertEquals(1L, accumulatedBeforeLeaving);

		tracker.onGameTick(OUTSIDE_GE, IDLE_DELAY_SECONDS);

		assertEquals(AuraState.IN_GAME, tracker.getState());
		assertEquals(accumulatedBeforeLeaving, session.getEligibleAuraDurationSeconds());
	}

	@Test
	public void logoutStopsTrackingAndSubsequentTicksAreNoOps()
	{
		enterGeAndStartEarning();
		accumulateOneRealisticTick();
		long totalBeforeLogout = session.getEligibleAuraDurationSeconds();

		tracker.onGameStateChanged(GameState.LOGIN_SCREEN);
		assertEquals(AuraState.LOGGED_OUT, tracker.getState());

		// Even if somehow called again with a valid GE location, a logged-out tracker
		// must not accumulate anything.
		fakeClock.advanceSeconds(10);
		tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);

		assertEquals(AuraState.LOGGED_OUT, tracker.getState());
		assertEquals(totalBeforeLogout, session.getEligibleAuraDurationSeconds());
	}

	@Test
	public void worldHopDoesNotGrantFreeAuraAndRequiresFreshIdleWait()
	{
		enterGeAndStartEarning();

		tracker.onGameStateChanged(GameState.HOPPING);
		assertEquals(AuraState.PAUSED, tracker.getState());

		// Simulate a long hop delay — must never be counted as eligible time.
		fakeClock.advanceSeconds(20);

		tracker.onGameStateChanged(GameState.LOGGED_IN);
		assertEquals(AuraState.IN_GAME, tracker.getState());

		// Landing back on the same tile after a hop must NOT resume EARNING_AURA
		// directly — it must re-establish the idle wait from scratch.
		tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);
		assertEquals(AuraState.WAITING_FOR_IDLE, tracker.getState());
	}

	@Test
	public void noDoubleCountingOverManyRealisticTicks()
	{
		enterGeAndStartEarning();

		// 100 ticks of 600ms == exactly 60 seconds of real elapsed time.
		for (int i = 0; i < 100; i++)
		{
			fakeClock.advanceMillis(600);
			tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);
		}

		assertEquals(60L, session.getCurrentSessionEligibleSeconds());
		assertEquals(60L, session.getEligibleAuraDurationSeconds());
	}

	@Test
	public void loggedOutTrackerIgnoresGameTicksEntirely()
	{
		AuraSessionTracker freshTracker = new AuraSessionTracker(
			plainSquareArea(),
			new AuraTimingClock(fakeClock),
			new AuraSession(),
			fakeClock);

		freshTracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);

		assertEquals(AuraState.LOGGED_OUT, freshTracker.getState());
	}

	private void enterGeAndStartEarning()
	{
		tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);
		fakeClock.advanceSeconds(IDLE_DELAY_SECONDS);
		tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);
		assertEquals(AuraState.EARNING_AURA, tracker.getState());
	}

	private void accumulateOneRealisticTick()
	{
		fakeClock.advanceMillis(1000);
		tracker.onGameTick(INSIDE_GE, IDLE_DELAY_SECONDS);
	}

	/**
	 * A plain 10x10 square, independent of the octagon-shaped production bounds — this
	 * test is about state-machine transitions, not the courtyard's real shape, so the
	 * diagonal bounds are left wide enough to never cut anything within the square.
	 */
	private static GrandExchangeArea plainSquareArea()
	{
		return new GrandExchangeArea(SW_X, SW_X + 9, SW_Y, SW_Y + 9, Integer.MIN_VALUE, Integer.MAX_VALUE,
			Integer.MIN_VALUE, Integer.MAX_VALUE, PLANE);
	}

	/**
	 * Controllable fake implementing both the {@link java.util.function.LongSupplier}
	 * seam used by {@link AuraSessionTracker} for idle-wait timestamps and the
	 * package-private {@link AuraTimingClock.NanoClock} seam used for earning deltas, so
	 * both can be driven from a single, deterministic time source in tests.
	 */
	private static final class FakeClock implements java.util.function.LongSupplier, AuraTimingClock.NanoClock
	{
		private long currentNanos;

		private FakeClock(long startNanos)
		{
			this.currentNanos = startNanos;
		}

		private void advanceSeconds(long seconds)
		{
			currentNanos += java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
		}

		private void advanceMillis(long millis)
		{
			currentNanos += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
		}

		@Override
		public long getAsLong()
		{
			return currentNanos;
		}

		@Override
		public long nanoTime()
		{
			return currentNanos;
		}
	}
}
