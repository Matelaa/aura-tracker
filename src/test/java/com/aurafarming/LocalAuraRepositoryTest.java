package com.aurafarming;

import com.google.gson.Gson;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class LocalAuraRepositoryTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private final Gson gson = new Gson();
	private static final long ACCOUNT_A = 111111L;
	private static final long ACCOUNT_B = 222222L;

	private LocalAuraRepository newRepository()
	{
		return new LocalAuraRepository(gson, temporaryFolder.getRoot());
	}

	@Test
	public void loadingWhenNoFileExistsReturnsFreshSession()
	{
		LocalAuraRepository repository = newRepository();

		AuraSession session = repository.load(ACCOUNT_A);

		assertNotNull(session);
		assertEquals(0L, session.getEligibleAuraDurationSeconds());
	}

	@Test
	public void saveThenLoadRoundTripsCorrectly()
	{
		LocalAuraRepository repository = newRepository();

		AuraSession original = new AuraSession();
		original.addEligibleSeconds(12345);
		repository.save(ACCOUNT_A, original);

		AuraSession loaded = repository.load(ACCOUNT_A);

		assertEquals(12345L, loaded.getEligibleAuraDurationSeconds());
		assertEquals(12345L, loaded.getCurrentSessionEligibleSeconds());
	}

	@Test
	public void twoAccountsNeverShareProgress()
	{
		// The actual bug this whole scoping exists to fix: logging into a second
		// RuneScape account on the same machine must not inherit the first account's
		// accumulated time.
		LocalAuraRepository repository = newRepository();

		AuraSession accountASession = new AuraSession();
		accountASession.addEligibleSeconds(500);
		repository.save(ACCOUNT_A, accountASession);

		AuraSession accountBSession = repository.load(ACCOUNT_B);

		assertEquals(0L, accountBSession.getEligibleAuraDurationSeconds());
	}

	@Test
	public void aDeviceIdIsIndependentPerAccount()
	{
		LocalAuraRepository repository = newRepository();

		AuraSession accountASession = repository.load(ACCOUNT_A);
		String deviceIdA = accountASession.getOrCreateDeviceId();
		repository.save(ACCOUNT_A, accountASession);

		AuraSession accountBSession = repository.load(ACCOUNT_B);
		String deviceIdB = accountBSession.getOrCreateDeviceId();
		repository.save(ACCOUNT_B, accountBSession);

		assertNotEquals(deviceIdA, deviceIdB);
	}

	@Test
	public void sameAccountKeepsItsProgressAcrossReloads()
	{
		// Simulates a rename: the account (accountHash) is the same even though the
		// character's display name would have changed — nothing about this repository
		// depends on display name, so progress survives.
		LocalAuraRepository repository = newRepository();

		AuraSession session = repository.load(ACCOUNT_A);
		session.addEligibleSeconds(9000);
		repository.save(ACCOUNT_A, session);

		AuraSession reloaded = repository.load(ACCOUNT_A);

		assertEquals(9000L, reloaded.getEligibleAuraDurationSeconds());
	}

	@Test
	public void corruptFileReturnsFreshSessionInsteadOfThrowing() throws IOException
	{
		File sessionsDir = temporaryFolder.getRoot();
		try (FileWriter writer = new FileWriter(new File(sessionsDir, ACCOUNT_A + ".json")))
		{
			writer.write("{ this is not valid json ][");
		}

		LocalAuraRepository repository = newRepository();
		AuraSession session = repository.load(ACCOUNT_A);

		assertNotNull(session);
		assertEquals(0L, session.getEligibleAuraDurationSeconds());
	}

	@Test
	public void negativeDurationInFileIsSanitizedToFreshSession() throws IOException
	{
		File sessionsDir = temporaryFolder.getRoot();
		try (FileWriter writer = new FileWriter(new File(sessionsDir, ACCOUNT_A + ".json")))
		{
			writer.write("{\"schemaVersion\":1,\"eligibleAuraDurationSeconds\":-500,"
				+ "\"currentSessionEligibleSeconds\":0,\"lastSavedAtEpochMillis\":0}");
		}

		LocalAuraRepository repository = newRepository();
		AuraSession session = repository.load(ACCOUNT_A);

		assertEquals(0L, session.getEligibleAuraDurationSeconds());
	}

	@Test
	public void implausiblyLargeDurationInFileIsSanitizedToFreshSession() throws IOException
	{
		// Data hygiene, not anti-cheat — see LocalAuraRepository's MAX_PLAUSIBLE_ELIGIBLE_SECONDS
		// javadoc for why a moderately-sized fabricated edit is NOT caught by this and
		// isn't meant to be; the real protection is server-side.
		File sessionsDir = temporaryFolder.getRoot();
		try (FileWriter writer = new FileWriter(new File(sessionsDir, ACCOUNT_A + ".json")))
		{
			writer.write("{\"schemaVersion\":1,\"eligibleAuraDurationSeconds\":999999999999,"
				+ "\"currentSessionEligibleSeconds\":0,\"lastSavedAtEpochMillis\":0}");
		}

		LocalAuraRepository repository = newRepository();
		AuraSession session = repository.load(ACCOUNT_A);

		assertEquals(0L, session.getEligibleAuraDurationSeconds());
	}
}
