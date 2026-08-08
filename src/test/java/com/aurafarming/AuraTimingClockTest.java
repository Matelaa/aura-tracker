package com.aurafarming;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AuraTimingClockTest
{
	@Test
	public void firstCallAfterConstructionReturnsZero()
	{
		FakeNanoClock fake = new FakeNanoClock(1_000_000_000L);
		AuraTimingClock clock = new AuraTimingClock(fake);

		assertEquals(0L, clock.elapsedNanosAndReset());
	}

	@Test
	public void normalTickDeltaIsReturnedExactly()
	{
		FakeNanoClock fake = new FakeNanoClock(0L);
		AuraTimingClock clock = new AuraTimingClock(fake);
		clock.reset();

		long tickNanos = TimeUnit.MILLISECONDS.toNanos(600); // a typical game tick
		fake.advance(tickNanos);

		assertEquals(tickNanos, clock.elapsedNanosAndReset());
	}

	@Test
	public void deltaAboveCapIsCappedNotDiscarded()
	{
		FakeNanoClock fake = new FakeNanoClock(0L);
		AuraTimingClock clock = new AuraTimingClock(fake);
		clock.reset();

		// A moderate lag spike: more than the cap, but well under the freeze threshold.
		fake.advance(TimeUnit.MILLISECONDS.toNanos(5000));

		assertEquals(AuraTimingClock.MAX_DELTA_NANOS, clock.elapsedNanosAndReset());
	}

	@Test
	public void deltaAboveFreezeThresholdIsDiscardedEntirely()
	{
		FakeNanoClock fake = new FakeNanoClock(0L);
		AuraTimingClock clock = new AuraTimingClock(fake);
		clock.reset();

		fake.advance(TimeUnit.SECONDS.toNanos(120)); // simulated freeze / long minimize

		assertEquals(0L, clock.elapsedNanosAndReset());
	}

	@Test
	public void consecutiveCallsDoNotDoubleCount()
	{
		FakeNanoClock fake = new FakeNanoClock(0L);
		AuraTimingClock clock = new AuraTimingClock(fake);
		clock.reset();

		long tick = TimeUnit.MILLISECONDS.toNanos(600);
		fake.advance(tick);
		long first = clock.elapsedNanosAndReset();

		fake.advance(tick);
		long second = clock.elapsedNanosAndReset();

		assertEquals(tick, first);
		assertEquals(tick, second);
		// Total across both calls equals exactly the total time advanced — nothing was
		// counted twice, and nothing was lost.
		assertEquals(2 * tick, first + second);
	}

	@Test
	public void clockGoingBackwardsNeverProducesNegativeDuration()
	{
		FakeNanoClock fake = new FakeNanoClock(1_000_000_000L);
		AuraTimingClock clock = new AuraTimingClock(fake);
		clock.reset();

		fake.set(500_000_000L); // clock moved backwards, which should never happen but must be defended against

		assertEquals(0L, clock.elapsedNanosAndReset());
	}

	/**
	 * Simple controllable fake implementing the package-private {@code NanoClock} seam.
	 */
	private static final class FakeNanoClock implements AuraTimingClock.NanoClock
	{
		private long currentNanos;

		private FakeNanoClock(long startNanos)
		{
			this.currentNanos = startNanos;
		}

		private void advance(long nanos)
		{
			this.currentNanos += nanos;
		}

		private void set(long nanos)
		{
			this.currentNanos = nanos;
		}

		@Override
		public long nanoTime()
		{
			return currentNanos;
		}
	}
}
