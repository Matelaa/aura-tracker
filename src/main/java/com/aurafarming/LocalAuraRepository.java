package com.aurafarming;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Loads and saves {@link AuraSession} data as local JSON files under the RuneLite
 * directory, one file per RuneScape account. This is the ONLY form of persistence in the
 * Phase A (local-only) scope of the project — there is no network client, no remote
 * sync, and no code path in this class that ever opens a socket or makes an HTTP
 * request.
 * <p>
 * Scoped by {@code accountHash} ({@link net.runelite.api.Client#getAccountHash()}),
 * not display name — the same choice RuneLite's own {@code ConfigManager} makes for
 * per-account state, and unlike the display-name-keyed screenshot folders, it survives a
 * character rename. Two different RuneScape accounts played on the same machine
 * therefore never share progress, and a single account keeps its progress across a
 * rename. {@code accountHash} is never sent anywhere by this class — see
 * {@link AuraSession#getOrCreateDeviceId()} for the opaque identifier actually used if
 * online sync is enabled.
 */
@Slf4j
public class LocalAuraRepository
{
	private static final String DIRECTORY_NAME = "aura-tracker";
	private static final String SESSIONS_SUBDIRECTORY = "sessions";

	/**
	 * Sanity ceiling for a hand-edited or corrupted local file — not anti-cheat (a
	 * moderately-sized fabricated edit sails straight through this, same as it always
	 * could), just data hygiene against obviously-absurd values. The real protection
	 * against a tampered file being used to seed the online leaderboard lives
	 * server-side (see aura-web's {@code applySync}: a brand-new device always starts
	 * at 0 there, regardless of what this file claims).
	 */
	private static final long MAX_PLAUSIBLE_ELIGIBLE_SECONDS = 100L * 365 * 24 * 60 * 60;

	private final Gson gson;
	private final File sessionsDir;

	@Inject
	public LocalAuraRepository(Gson gson)
	{
		this(gson, new File(new File(RuneLite.RUNELITE_DIR, DIRECTORY_NAME), SESSIONS_SUBDIRECTORY));
	}

	/**
	 * Package-private constructor allowing tests to point at a temporary directory
	 * instead of the real RuneLite directory.
	 */
	LocalAuraRepository(Gson gson, File sessionsDir)
	{
		this.gson = gson;
		this.sessionsDir = sessionsDir;
		if (!sessionsDir.exists())
		{
			sessionsDir.mkdirs();
		}
	}

	/**
	 * Loads the persisted session for this account, or returns a fresh, zeroed
	 * {@link AuraSession} if no file exists yet for it, or if the file is missing,
	 * corrupt, or contains invalid data. Never throws — a bad local file should never
	 * prevent the plugin from starting.
	 */
	public AuraSession load(long accountHash)
	{
		File file = fileFor(accountHash);
		if (!file.exists())
		{
			return new AuraSession();
		}

		try (Reader reader = new FileReader(file))
		{
			AuraSession loaded = gson.fromJson(reader, AuraSession.class);
			return sanitize(loaded);
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.warn("Aura Tracker: failed to load local session file, starting fresh", e);
			return new AuraSession();
		}
	}

	/**
	 * Persists the given session to this account's file, overwriting any previous
	 * version. Failures are logged, never thrown — a failed save should never crash the
	 * client or interrupt gameplay.
	 */
	public void save(long accountHash, AuraSession session)
	{
		session.setLastSavedAtEpochMillis(System.currentTimeMillis());

		try (Writer writer = new FileWriter(fileFor(accountHash)))
		{
			gson.toJson(session, writer);
		}
		catch (IOException e)
		{
			log.warn("Aura Tracker: failed to save local session file", e);
		}
	}

	private File fileFor(long accountHash)
	{
		return new File(sessionsDir, accountHash + ".json");
	}

	/**
	 * Defends against a hand-edited or corrupted file producing an invalid session
	 * (e.g. negative durations), rather than trusting the file's contents blindly.
	 */
	private AuraSession sanitize(AuraSession loaded)
	{
		if (loaded == null
			|| loaded.getEligibleAuraDurationSeconds() < 0
			|| loaded.getEligibleAuraDurationSeconds() > MAX_PLAUSIBLE_ELIGIBLE_SECONDS)
		{
			return new AuraSession();
		}

		return loaded;
	}
}
