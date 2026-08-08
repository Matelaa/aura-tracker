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
 * Loads and saves {@link AuraSession} data as a single local JSON file under the
 * RuneLite directory. This is the ONLY form of persistence in the Phase A (local-only)
 * scope of the project — there is no network client, no remote sync, and no code path
 * in this class that ever opens a socket or makes an HTTP request.
 * <p>
 * MVP scope note: data is stored in one file per RuneLite installation, not scoped per
 * RuneScape profile. Splitting by profile is a reasonable future enhancement but is not
 * required for the MVP and was intentionally left out to keep this class simple.
 */
@Slf4j
public class LocalAuraRepository
{
	private static final String DIRECTORY_NAME = "aura-tracker";
	private static final String FILE_NAME = "session.json";

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
	private final File file;

	@Inject
	public LocalAuraRepository(Gson gson)
	{
		this(gson, new File(new File(RuneLite.RUNELITE_DIR, DIRECTORY_NAME), FILE_NAME));
	}

	/**
	 * Package-private constructor allowing tests to point at a temporary file instead of
	 * the real RuneLite directory.
	 */
	LocalAuraRepository(Gson gson, File file)
	{
		this.gson = gson;
		this.file = file;
		File parentDir = file.getParentFile();
		if (parentDir != null && !parentDir.exists())
		{
			parentDir.mkdirs();
		}
	}

	/**
	 * Loads the persisted session, or returns a fresh, zeroed {@link AuraSession} if no
	 * file exists yet, or if the file is missing, corrupt, or contains invalid data.
	 * Never throws — a bad local file should never prevent the plugin from starting.
	 */
	public AuraSession load()
	{
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
	 * Persists the given session to disk, overwriting any previous file. Failures are
	 * logged, never thrown — a failed save should never crash the client or interrupt
	 * gameplay.
	 */
	public void save(AuraSession session)
	{
		session.setLastSavedAtEpochMillis(System.currentTimeMillis());

		try (Writer writer = new FileWriter(file))
		{
			gson.toJson(session, writer);
		}
		catch (IOException e)
		{
			log.warn("Aura Tracker: failed to save local session file", e);
		}
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
