package com.aurafarming;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;

/**
 * Owns the Aura tracking state machine. This is the only class in the plugin that
 * decides whether Aura is currently accumulating; everything else (panel, overlay,
 * repository) only reads from it.
 * <p>
 * Only ever consulted with the LOCAL player's own {@link WorldPoint} and the client's
 * own {@link GameState}. No other player's position, appearance, or actions are ever
 * read by this class, and none should ever be passed into it.
 */
public class AuraSessionTracker
{
	private final GrandExchangeArea grandExchangeArea;
	private final AuraTimingClock earningClock;
	private final LongSupplier nanoTimeSupplier;

	private AuraSession session;
	private AuraState state = AuraState.LOGGED_OUT;
	private WorldPoint anchorTile;
	private long idleStartNanos;

	/**
	 * Nanoseconds of eligible time accumulated but not yet converted into a whole
	 * second. A game tick is ~600ms, so without this carry-over, truncating every tick
	 * to whole seconds would silently discard almost all eligible time.
	 */
	private long carryNanos;

	public AuraSessionTracker(GrandExchangeArea grandExchangeArea, AuraTimingClock earningClock, AuraSession session)
	{
		this(grandExchangeArea, earningClock, session, System::nanoTime);
	}

	/**
	 * Package-private constructor allowing tests to inject a fake clock, so state
	 * machine tests never depend on real wall-clock sleeps.
	 */
	AuraSessionTracker(GrandExchangeArea grandExchangeArea, AuraTimingClock earningClock, AuraSession session,
						LongSupplier nanoTimeSupplier)
	{
		this.grandExchangeArea = grandExchangeArea;
		this.earningClock = earningClock;
		this.session = session;
		this.nanoTimeSupplier = nanoTimeSupplier;
	}

	public AuraState getState()
	{
		return state;
	}

	public AuraSession getSession()
	{
		return session;
	}

	/**
	 * Replaces the tracked session, e.g. after loading persisted data at startup.
	 * Does not touch the current state machine state.
	 */
	public void setSession(AuraSession session)
	{
		this.session = session;
	}

	/**
	 * Must be called once per {@code GameTick} with the local player's own world
	 * location. Pass {@code null} if the local player is not currently resolvable
	 * (e.g. mid-loading) — this is treated the same as being outside the Grand Exchange.
	 *
	 * @param localPlayerLocation the local player's own {@code WorldPoint}, or null.
	 * @param idleDelaySeconds    seconds a player must stand still before Aura starts
	 *                            accumulating (from {@code AuraConfig}).
	 */
	public void onGameTick(WorldPoint localPlayerLocation, int idleDelaySeconds)
	{
		if (state == AuraState.LOGGED_OUT)
		{
			return;
		}

		boolean inGrandExchange = localPlayerLocation != null && grandExchangeArea.contains(localPlayerLocation);

		if (!inGrandExchange)
		{
			if (state != AuraState.IN_GAME)
			{
				state = AuraState.IN_GAME;
				anchorTile = null;
			}
			return;
		}

		// Player is inside the Grand Exchange this tick.
		if (state == AuraState.IN_GAME || state == AuraState.PAUSED)
		{
			state = AuraState.IN_GE;
		}

		if (state == AuraState.IN_GE)
		{
			// (Re-)establish the anchor tile and begin the idle wait immediately — this
			// state is transitional and does not persist across ticks by design.
			anchorTile = localPlayerLocation;
			idleStartNanos = nanoTimeSupplier.getAsLong();
			state = AuraState.WAITING_FOR_IDLE;
			return;
		}

		// state is WAITING_FOR_IDLE or EARNING_AURA from here.
		if (!localPlayerLocation.equals(anchorTile))
		{
			// Moved tiles: reset the idle wait, whether we were waiting or already earning.
			anchorTile = localPlayerLocation;
			idleStartNanos = nanoTimeSupplier.getAsLong();
			state = AuraState.WAITING_FOR_IDLE;
			return;
		}

		if (state == AuraState.WAITING_FOR_IDLE)
		{
			long idleElapsedNanos = nanoTimeSupplier.getAsLong() - idleStartNanos;
			long idleDelayNanos = TimeUnit.SECONDS.toNanos(Math.max(0, idleDelaySeconds));
			if (idleElapsedNanos >= idleDelayNanos)
			{
				state = AuraState.EARNING_AURA;
				// Start counting from this instant, not from when the tile was first reached.
				earningClock.reset();
			}
			return;
		}

		// state == EARNING_AURA, still on the anchor tile: accumulate.
		long deltaNanos = earningClock.elapsedNanosAndReset();
		carryNanos += deltaNanos;

		long wholeSeconds = carryNanos / 1_000_000_000L;
		if (wholeSeconds > 0)
		{
			session.addEligibleSeconds(wholeSeconds);
			carryNanos -= wholeSeconds * 1_000_000_000L;
		}
	}

	/**
	 * Must be called whenever the client's {@link GameState} changes. Handles login,
	 * logout, world hops, loading screens, and connection loss by pausing or resetting
	 * tracking as appropriate — never by inferring anything from other players or
	 * from input devices.
	 */
	public void onGameStateChanged(GameState newState)
	{
		switch (newState)
		{
			case LOGIN_SCREEN:
			case LOGIN_SCREEN_AUTHENTICATOR:
			case CONNECTION_LOST:
				state = AuraState.LOGGED_OUT;
				anchorTile = null;
				carryNanos = 0;
				break;
			case HOPPING:
			case LOADING:
			case LOGGING_IN:
				if (state != AuraState.LOGGED_OUT)
				{
					state = AuraState.PAUSED;
					anchorTile = null;
				}
				break;
			case LOGGED_IN:
				if (state == AuraState.LOGGED_OUT)
				{
					state = AuraState.IN_GAME;
				}
				else if (state == AuraState.PAUSED)
				{
					state = AuraState.IN_GAME;
				}
				// The next onGameTick call re-establishes GE membership and, if
				// applicable, the idle wait from scratch — nothing is assumed here.
				break;
			default:
				// Unknown/unused states are ignored rather than guessed at.
				break;
		}
	}
}
