package com.aurafarming;

import java.util.UUID;

/**
 * Persisted data for a player's Aura progress.
 * <p>
 * Deliberately holds only the raw eligible duration, never a computed Aura score. The
 * score is always derived on demand via {@link AuraScoreCalculator}, so the scoring
 * formula can change later without requiring a data migration.
 */
public class AuraSession
{
	private static final int CURRENT_SCHEMA_VERSION = 2;

	private int schemaVersion = CURRENT_SCHEMA_VERSION;

	/**
	 * Total eligible seconds accumulated across all time. This is the source of truth.
	 */
	private long eligibleAuraDurationSeconds;

	/**
	 * Eligible seconds accumulated during the current client session only. Resettable,
	 * shown in the UI as "current session".
	 */
	private long currentSessionEligibleSeconds;

	/**
	 * Wall-clock time of the last successful save, for diagnostics only. Never used for
	 * duration math.
	 */
	private long lastSavedAtEpochMillis;

	/**
	 * Opaque identifier, generated locally once and reused thereafter, used only if/when
	 * online sync is enabled (see {@link AuraApiClient}). Never a Jagex account identity
	 * — see the backend's own documentation of that distinction. Null/absent for
	 * sessions from before online sync existed or for anyone who never opts in; those
	 * never generate one, since it would otherwise be pointless data with no consumer.
	 */
	private String deviceId;

	/**
	 * Consecutive online syncs sent with {@link #eligibleAuraDurationSeconds} still at
	 * zero. Used only to gate the one-time "you haven't earned any Aura yet" chat
	 * message (see {@code AuraPlugin#doSyncOnline}) so it never fires on a fresh
	 * install's very first sync — only after a real stretch of continued, unproductive
	 * play. Never decremented: once the total becomes positive it stays positive
	 * forever (see {@link #addEligibleSeconds}), so the streak becoming irrelevant
	 * needs no explicit reset.
	 */
	private int zeroAuraSyncStreak;

	public AuraSession()
	{
		// Default constructor for Gson deserialization.
	}

	/**
	 * Returns the existing device id, or generates and retains a new one on first call.
	 * Deliberately lazy rather than always-generated: a session that never opts into
	 * online sync should never end up with an identifier serving no purpose.
	 */
	public String getOrCreateDeviceId()
	{
		if (deviceId == null || deviceId.isEmpty())
		{
			deviceId = UUID.randomUUID().toString();
		}
		return deviceId;
	}

	public int getSchemaVersion()
	{
		return schemaVersion;
	}

	public long getEligibleAuraDurationSeconds()
	{
		return eligibleAuraDurationSeconds;
	}

	public long getCurrentSessionEligibleSeconds()
	{
		return currentSessionEligibleSeconds;
	}

	public long getLastSavedAtEpochMillis()
	{
		return lastSavedAtEpochMillis;
	}

	public int getZeroAuraSyncStreak()
	{
		return zeroAuraSyncStreak;
	}

	/** Called once per online sync attempt while the all-time total is still zero. */
	public void recordZeroAuraSync()
	{
		zeroAuraSyncStreak++;
	}

	public void setLastSavedAtEpochMillis(long lastSavedAtEpochMillis)
	{
		this.lastSavedAtEpochMillis = lastSavedAtEpochMillis;
	}

	/**
	 * Adds eligible seconds to both the all-time total and the current session total.
	 * Never accepts a negative value, which would otherwise let a corrupted or
	 * maliciously modified delta reduce the player's total.
	 */
	public void addEligibleSeconds(long seconds)
	{
		if (seconds <= 0)
		{
			return;
		}

		eligibleAuraDurationSeconds += seconds;
		currentSessionEligibleSeconds += seconds;
	}

	/**
	 * Resets only the current-session counter (e.g. on plugin restart), leaving the
	 * all-time total untouched.
	 */
	public void resetSession()
	{
		currentSessionEligibleSeconds = 0;
	}
}
