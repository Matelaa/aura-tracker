package com.aurafarming;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class LocalAuraRepositoryTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private final Gson gson = new Gson();

	@Test
	public void loadingWhenNoFileExistsReturnsFreshSession()
	{
		File file = new File(temporaryFolder.getRoot(), "does-not-exist.json");
		LocalAuraRepository repository = new LocalAuraRepository(gson, file);

		AuraSession session = repository.load();

		assertNotNull(session);
		assertEquals(0L, session.getEligibleAuraDurationSeconds());
	}

	@Test
	public void saveThenLoadRoundTripsCorrectly() throws IOException
	{
		File file = temporaryFolder.newFile("session.json");
		LocalAuraRepository repository = new LocalAuraRepository(gson, file);

		AuraSession original = new AuraSession();
		original.addEligibleSeconds(12345);
		repository.save(original);

		AuraSession loaded = repository.load();

		assertEquals(12345L, loaded.getEligibleAuraDurationSeconds());
		assertEquals(12345L, loaded.getCurrentSessionEligibleSeconds());
	}

	@Test
	public void corruptFileReturnsFreshSessionInsteadOfThrowing() throws IOException
	{
		File file = temporaryFolder.newFile("corrupt.json");
		try (FileWriter writer = new FileWriter(file))
		{
			writer.write("{ this is not valid json ][");
		}

		LocalAuraRepository repository = new LocalAuraRepository(gson, file);
		AuraSession session = repository.load();

		assertNotNull(session);
		assertEquals(0L, session.getEligibleAuraDurationSeconds());
	}

	@Test
	public void negativeDurationInFileIsSanitizedToFreshSession() throws IOException
	{
		File file = temporaryFolder.newFile("negative.json");
		try (FileWriter writer = new FileWriter(file))
		{
			writer.write("{\"schemaVersion\":1,\"eligibleAuraDurationSeconds\":-500,"
				+ "\"currentSessionEligibleSeconds\":0,\"lastSavedAtEpochMillis\":0}");
		}

		LocalAuraRepository repository = new LocalAuraRepository(gson, file);
		AuraSession session = repository.load();

		assertEquals(0L, session.getEligibleAuraDurationSeconds());
	}

	@Test
	public void implausiblyLargeDurationInFileIsSanitizedToFreshSession() throws IOException
	{
		// Data hygiene, not anti-cheat — see LocalAuraRepository's MAX_PLAUSIBLE_ELIGIBLE_SECONDS
		// javadoc for why a moderately-sized fabricated edit is NOT caught by this and
		// isn't meant to be; the real protection is server-side.
		File file = temporaryFolder.newFile("toolarge.json");
		try (FileWriter writer = new FileWriter(file))
		{
			writer.write("{\"schemaVersion\":1,\"eligibleAuraDurationSeconds\":999999999999,"
				+ "\"currentSessionEligibleSeconds\":0,\"lastSavedAtEpochMillis\":0}");
		}

		LocalAuraRepository repository = new LocalAuraRepository(gson, file);
		AuraSession session = repository.load();

		assertEquals(0L, session.getEligibleAuraDurationSeconds());
	}
}
