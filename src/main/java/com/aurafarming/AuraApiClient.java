package com.aurafarming;

import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends the local player's own Aura total to the (opt-out, on-by-default since
 * 2026-08-07) community leaderboard backend. Only ever constructed and used when
 * {@link AuraConfig#onlineSyncEnabled()} is true — this class itself has no opinion on
 * that decision, it just performs the sync once told to.
 * <p>
 * Uses {@link HttpClient}, built into the JDK since 11 — no new dependency. A sync
 * failure (network down, backend unreachable, timeout) is always swallowed, logged at
 * debug level, and never surfaces to the user or affects gameplay in any way; the next
 * scheduled sync simply tries again.
 */
@Slf4j
@Singleton
public class AuraApiClient
{
	/**
	 * The real, deployed backend (Vercel, backed by Neon Postgres) as of 2026-08-08 —
	 * see aura-back/README.md.
	 */
	static final String PRODUCTION_BASE_URL = "https://aura-back-eta.vercel.app";

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

	private final HttpClient httpClient;
	private final Gson gson;
	private final String baseUrl;
	private final LongSupplier nanoTime;
	private final ConcurrentHashMap<String, Long> retryAfter = new ConcurrentHashMap<>();
	private final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();
	// Plugin Hub's standard build replaces build.gradle; do not rely on generated resources.
	// AuraApiClientTest checks this against runelite-plugin.properties on every test run.
	static final String CLIENT_VERSION = "1.6";

	/**
	 * Always takes RuneLite's own injected {@link Gson} instance rather than
	 * constructing a fresh one — a plain {@code new Gson()} is a terminally deprecated
	 * pattern the Plugin Hub packager rejects at build time (confirmed by an actual
	 * failed submission, not just the docs).
	 */
	@Inject
	public AuraApiClient(Gson gson)
	{
		this(gson, PRODUCTION_BASE_URL);
	}

	/**
	 * Package-private constructor allowing tests to point at a local test server instead
	 * of the real (or dev) backend.
	 */
	AuraApiClient(Gson gson, String baseUrl)
	{
		this(gson, baseUrl, HttpClient.newBuilder()
			.connectTimeout(REQUEST_TIMEOUT)
			// HttpClient defaults to preferring HTTP/2 even over plain (non-TLS) HTTP,
			// which negotiates via an h2c upgrade handshake. Node's dev server (and
			// likely plenty of other simple HTTP/1.1-only servers) doesn't handle that
			// handshake and just drops the connection, surfacing as a confusing
			// "HTTP/1.1 header parser received no bytes" / EOFException — not a timeout,
			// not a refused connection, just a silently closed socket. Reproduced this
			// exact failure locally and confirmed forcing HTTP/1.1 fixes it before
			// applying this change.
			.version(HttpClient.Version.HTTP_1_1)
			.build(), System::nanoTime);
	}

	AuraApiClient(Gson gson, String baseUrl, HttpClient httpClient, LongSupplier nanoTime)
	{
		this.baseUrl = baseUrl;
		this.gson = gson;
		this.httpClient = httpClient;
		this.nanoTime = nanoTime;
	}

	/**
	 * The base URL this client is configured to send data to — used only to show the
	 * user exactly where their data goes in the consent dialog (see
	 * {@link AuraPlugin#confirmAndStartOnlineSync()}), never hidden from them.
	 */
	public String baseUrlForDisplay()
	{
		return baseUrl;
	}

	/**
	 * Fire-and-forget: returns immediately, the actual request completes asynchronously.
	 * Never throws.
	 *
	 * @param accountHash {@code Client#getAccountHash()}, or {@code -1} if not resolvable
	 *                     right now — sent as a string (a {@code long} can exceed JS's
	 *                     safe integer range) and only when known; the field is simply
	 *                     omitted from the JSON body for {@code -1} rather than sent as a
	 *                     sentinel, since the backend already treats a missing value and
	 *                     an older, not-yet-updated plugin install identically.
	 */
	public void syncAsync(String deviceId, String displayName, long eligibleSecondsTotal, long accountHash)
	{
		if (deviceId == null || !inFlight.add(deviceId))
		{
			return;
		}
		// Acquire the slot first: another response could set a cooldown between checking
		// it and acquiring the slot. Recheck while this call owns the device slot.
		if (!canSync(deviceId))
		{
			inFlight.remove(deviceId);
			return;
		}

		HttpRequest request;
		try
		{
			String accountHashValue = accountHash == -1 ? null : String.valueOf(accountHash);
			String json = gson.toJson(new SyncRequestBody(deviceId, displayName, eligibleSecondsTotal, accountHashValue));
			request = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/api/sync"))
				.version(HttpClient.Version.HTTP_1_1)
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.header("X-Aura-Client-Version", CLIENT_VERSION)
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build();
		}
		catch (RuntimeException e)
		{
			// Malformed baseUrl, etc. — should never happen with a hardcoded/validated
			// URL, but a sync must never throw regardless of the reason.
			log.warn("Aura Tracker: could not build sync request", e);
			inFlight.remove(deviceId);
			return;
		}

		log.info("Aura Tracker: sending online sync to {}", request.uri());

		try
		{
			httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.orTimeout(REQUEST_TIMEOUT.toSeconds() + 2, TimeUnit.SECONDS)
				.whenComplete((response, error) ->
				{
					try { handleSyncResponse(deviceId, response, error); }
					finally { inFlight.remove(deviceId); }
				});
		}
		catch (RuntimeException error)
		{
			inFlight.remove(deviceId);
			log.warn("Aura Tracker: could not send sync request", error);
		}
	}

	private void handleSyncResponse(String deviceId, HttpResponse<String> response, Throwable error)
	{
		if (error != null)
		{
			log.warn("Aura Tracker: online sync failed, will retry next cycle", error);
			return;
		}
		String requestId = response.headers().firstValue("X-Request-Id").orElse("unknown");
		if (response.statusCode() == 200)
		{
			SyncReply reply = parseSyncReply(response.body());
			if (reply != null && Boolean.TRUE.equals(reply.accepted))
			{
				log.info("Aura Tracker: sync accepted, server seconds={}, requestId={}", reply.totalEligibleSeconds, requestId);
			}
			else
			{
				String reason = reply == null ? "invalid_response" : reply.rejectionReason;
				log.warn("Aura Tracker: sync not accepted in full, reason={}, requestId={}", reason, requestId);
			}
		}
		else
		{
			if (response.statusCode() == 429)
			{
				deferSync(deviceId, response.headers().firstValue("Retry-After").orElse("240"));
			}
			log.warn("Aura Tracker: online sync rejected, status={}, requestId={}", response.statusCode(), requestId);
		}
	}

	boolean canSync(String deviceId)
	{
		Long deadline = retryAfter.get(deviceId);
		if (deadline == null) return true;
		if (nanoTime.getAsLong() - deadline < 0) return false;
		retryAfter.remove(deviceId, deadline);
		return true;
	}

	void deferSync(String deviceId, String secondsHeader)
	{
		long seconds = 240;
		try { seconds = Math.max(1, Math.min(3600, Long.parseLong(secondsHeader))); }
		catch (NumberFormatException ignored) { /* Use the server's standard sync window. */ }
		retryAfter.put(deviceId, nanoTime.getAsLong() + TimeUnit.SECONDS.toNanos(seconds));
	}

	SyncReply parseSyncReply(String body)
	{
		try
		{
			SyncReply reply = gson.fromJson(body, SyncReply.class);
			return reply != null && reply.accepted != null ? reply : null;
		}
		catch (RuntimeException ignored) { return null; }
	}

	static final class SyncReply
	{
		Boolean accepted;
		String rejectionReason;
		long totalEligibleSeconds;
	}

	/**
	 * Fire-and-forget upload of the player's exported 3D model (see
	 * {@link com.aurafarming.modelexporter.GlbExporter}) for the profile page's model
	 * viewer. Same failure contract as {@link #syncAsync}: never throws, a failure is
	 * logged and simply tried again next cycle. {@code deviceId} travels as a query
	 * parameter because the body itself is raw binary, not JSON.
	 */
	public void syncModelAsync(String deviceId, byte[] glb)
	{
		HttpRequest request;
		try
		{
			request = HttpRequest.newBuilder()
				.uri(URI.create(baseUrl + "/api/players/model?deviceId=" + deviceId))
				.version(HttpClient.Version.HTTP_1_1)
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "model/gltf-binary")
				.header("X-Aura-Client-Version", CLIENT_VERSION)
				.POST(HttpRequest.BodyPublishers.ofByteArray(glb))
				.build();
		}
		catch (RuntimeException e)
		{
			log.warn("Aura Tracker: could not build model upload request", e);
			return;
		}

		log.info("Aura Tracker: uploading player model ({} bytes) to {}", glb.length, request.uri());

		httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.orTimeout(REQUEST_TIMEOUT.toSeconds() + 2, TimeUnit.SECONDS)
			.whenComplete((response, error) ->
			{
				if (error != null)
				{
					log.warn("Aura Tracker: model upload failed, will retry next cycle", error);
					return;
				}
				if (response.statusCode() == 200)
				{
					log.info("Aura Tracker: model upload accepted");
				}
				else
				{
					log.warn("Aura Tracker: model upload rejected by server, status={}, body={}",
						response.statusCode(), response.body());
				}
			});
	}

	private static final class SyncRequestBody
	{
		// Field names are serialized as-is by Gson — must match the backend's zod schema
		// (aura-back/src/lib/schemas.ts) exactly: deviceId, displayName,
		// eligibleSecondsTotal, accountHash. A null accountHash is omitted from the JSON
		// entirely (Gson's default behavior), not sent as a null/sentinel value.
		private final String deviceId;
		private final String displayName;
		private final long eligibleSecondsTotal;
		private final String accountHash;

		private SyncRequestBody(String deviceId, String displayName, long eligibleSecondsTotal, String accountHash)
		{
			this.deviceId = deviceId;
			this.displayName = displayName;
			this.eligibleSecondsTotal = eligibleSecondsTotal;
			this.accountHash = accountHash;
		}
	}
}
