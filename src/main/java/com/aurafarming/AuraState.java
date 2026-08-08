package com.aurafarming;

/**
 * States of the Aura tracking state machine.
 * <p>
 * Transitions are driven entirely by {@link AuraSessionTracker}, based on the local
 * player's own {@code WorldPoint} and the RuneLite {@code GameState}. No other player's
 * data, and no keyboard/mouse/process state, is ever consulted.
 */
public enum AuraState
{
	/**
	 * Not logged in to a game world.
	 */
	LOGGED_OUT,

	/**
	 * Logged in, but not currently inside the Grand Exchange area.
	 */
	IN_GAME,

	/**
	 * Inside the Grand Exchange area, but has moved tiles too recently to count as idle.
	 */
	IN_GE,

	/**
	 * Inside the Grand Exchange, standing on a tile, waiting for the configured idle
	 * delay to elapse before Aura starts accumulating.
	 */
	WAITING_FOR_IDLE,

	/**
	 * Inside the Grand Exchange, stationary long enough that Aura is actively accumulating.
	 */
	EARNING_AURA,

	/**
	 * Tracking is temporarily suspended (world hop, loading screen, teleport, death, etc.)
	 * while the client state is not reliable enough to safely resume counting.
	 */
	PAUSED
}
