package com.aurafarming;

import com.google.gson.Gson;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Controlled HTTP completions and monotonic time: no sleeps or live API calls. */
public class AuraApiClientRecoveryTest
{
	private HttpClient transport;
	private AuraApiClient client;
	private AtomicLong clock;
	private CompletableFuture<HttpResponse<String>> pending;

	@Before
	public void setUp()
	{
		transport = mock(HttpClient.class);
		clock = new AtomicLong();
		pending = new CompletableFuture<>();
		when(transport.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
		client = new AuraApiClient(new Gson(), "http://local.test", transport, clock::get);
	}

	@SuppressWarnings("unchecked")
	private HttpResponse<String> response(int status, String retry, String body)
	{
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.body()).thenReturn(body);
		when(response.headers()).thenReturn(HttpHeaders.of(retry == null ? Collections.emptyMap() : Collections.singletonMap("Retry-After", List.of(retry)), (k, v) -> true));
		return response;
	}

	private void send(String device)
	{
		client.syncAsync(device, "Synthetic", 120, -1);
	}

	private void sent(int count)
	{
		verify(transport, times(count)).sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
	}

	@Test
	public void rejectsOverlappingCallsButReleasesSlotAfterSuccess()
	{
		send("one");
		send("one");
		sent(1);
		pending.complete(response(200, null, "{\"accepted\":true}"));
		send("one");
		sent(2);
	}

	@Test
	public void respectsActual429AndResumesAtExpiry()
	{
		send("one");
		pending.complete(response(429, "120", "{}"));
		send("one");
		sent(1);
		clock.set(TimeUnit.SECONDS.toNanos(119));
		send("one");
		sent(1);
		clock.set(TimeUnit.SECONDS.toNanos(120));
		send("one");
		sent(2);
	}

	@Test
	public void blockedAccountDoesNotBlockAnotherAccount()
	{
		send("one");
		pending.complete(response(429, "120", "{}"));
		send("two");
		sent(2);
	}

	@Test
	public void connectionFailureReleasesSlot()
	{
		send("one");
		pending.completeExceptionally(new java.io.IOException("test connection failure"));
		send("one");
		sent(2);
	}

	@Test
	public void timeoutReleasesSlot()
	{
		send("one");
		pending.completeExceptionally(new java.net.http.HttpTimeoutException("test timeout"));
		send("one");
		sent(2);
	}

	@Test
	public void malformedSuccessDoesNotBlockFutureSyncs()
	{
		send("one");
		pending.complete(response(200, null, "<html>error</html>"));
		send("one");
		sent(2);
	}

	@Test
	public void businessRejectionDoesNotIntroduceACooldown()
	{
		send("one");
		pending.complete(response(200, null, "{\"accepted\":false,\"rejectionReason\":\"regression\"}"));
		assertTrue(client.canSync("one"));
		send("one");
		sent(2);
	}

	@Test
	public void synchronousTransportFailureDoesNotPermanentlyBlockDevice()
	{
		when(transport.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new IllegalStateException("test closed transport"));
		send("one");
		send("one");
		sent(2);
	}

	@Test
	public void missingRetryHeaderFallsBackAndEventuallyExpires()
	{
		send("one");
		pending.complete(response(429, null, "{}"));
		clock.set(TimeUnit.SECONDS.toNanos(239));
		assertFalse(client.canSync("one"));
		clock.set(TimeUnit.SECONDS.toNanos(240));
		assertTrue(client.canSync("one"));
	}
}
