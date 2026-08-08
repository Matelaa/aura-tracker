package com.aurafarming;

import java.util.concurrent.TimeUnit;

/**
 * Measures elapsed time between successive calls using a monotonic clock, with
 * protections against lag spikes, client freezes, and clock manipulation.
 * <p>
 * Deliberately uses {@link System#nanoTime()} rather than {@link System#currentTimeMillis()}:
 * {@code nanoTime} is monotonic and immune to system clock changes (NTP adjustments,
 * manual clock changes, daylight saving), while {@code currentTimeMillis} is not.
 * {@code GameTick} counting ("1 point per tick") is deliberately avoided because tick
 * duration is nominally ~600ms but varies under server lag or client hitches, which
 * would cause drift and incorrect scoring.
 * <p>
 * Returns nanoseconds rather than seconds: a game tick is ~600ms, so truncating every
 * call to whole seconds would silently discard almost all elapsed time. Callers are
 * expected to accumulate the returned nanoseconds and only convert to whole seconds once
 * enough has built up (see {@link AuraSessionTracker}), carrying any remainder forward.
 */
public class AuraTimingClock
{
	/**
	 * Maximum delta counted from a single measurement, in nanoseconds (~2 game ticks).
	 * Caps the impact of an occasional lag spike so a brief stall can't be counted as if
	 * the player had been idle the whole time.
	 */
	static final long MAX_DELTA_NANOS = TimeUnit.MILLISECONDS.toNanos(1200);

	/**
	 * Above this threshold, the delta is assumed to be a freeze, a long lag spike, or the
	 * client having been minimized/suspended for a long time, and is discarded entirely
	 * rather than counted — the player should not "wake up" with a large amount of Aura
	 * for having done nothing while the client was frozen.
	 */
	static final long FREEZE_THRESHOLD_NANOS = TimeUnit.SECONDS.toNanos(30);

	private final NanoClock nanoClock;
	private long lastNanos;
	private boolean started;

	public AuraTimingClock()
	{
		this(System::nanoTime);
	}

	/**
	 * Package-private constructor allowing tests to inject a fake clock.
	 */
	AuraTimingClock(NanoClock nanoClock)
	{
		this.nanoClock = nanoClock;
	}

	/**
	 * Marks the current instant as the new reference point, without returning an elapsed
	 * duration. Call this whenever accumulation should NOT include the time since the
	 * last checkpoint (e.g. transitioning into EARNING_AURA for the first time, or
	 * resuming after a pause).
	 */
	public void reset()
	{
		lastNanos = nanoClock.nanoTime();
		started = true;
	}

	/**
	 * Returns the capped, non-negative elapsed duration in nanoseconds since the last
	 * call to {@link #reset()} or {@link #elapsedNanosAndReset()}, and resets the
	 * reference point to now.
	 * <p>
	 * If this is the first call since construction (no prior {@link #reset()}), returns 0
	 * and simply establishes the reference point — this avoids ever counting time that
	 * elapsed before tracking meaningfully started.
	 */
	public long elapsedNanosAndReset()
	{
		long now = nanoClock.nanoTime();

		if (!started)
		{
			lastNanos = now;
			started = true;
			return 0L;
		}

		long deltaNanos = now - lastNanos;
		lastNanos = now;

		if (deltaNanos <= 0)
		{
			// Impossible under a monotonic clock in practice, but never allow a negative
			// or zero delta to produce anything but zero.
			return 0L;
		}

		if (deltaNanos > FREEZE_THRESHOLD_NANOS)
		{
			// Likely a freeze, long minimize, or suspend/resume. Discard rather than count.
			return 0L;
		}

		return Math.min(deltaNanos, MAX_DELTA_NANOS);
	}

	/**
	 * Thin seam over {@link System#nanoTime()} so tests can control the clock without
	 * sleeping real time.
	 */
	@FunctionalInterface
	interface NanoClock
	{
		long nanoTime();
	}
}
