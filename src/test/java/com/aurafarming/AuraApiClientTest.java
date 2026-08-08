package com.aurafarming;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Uses a real, ephemeral local HTTP server ({@link HttpServer}, built into the JDK) as a
 * test double for the backend — verifies the actual bytes {@link AuraApiClient} sends
 * over the wire, not just that some method was called.
 */
public class AuraApiClientTest
{
	private HttpServer server;
	private volatile String lastRequestBody;
	private volatile String lastRequestMethod;
	private volatile String lastContentType;
	private CountDownLatch requestReceived;
	private int respondWithStatus = 200;

	@Before
	public void setUp() throws IOException
	{
		requestReceived = new CountDownLatch(1);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/sync", this::handleSync);
		server.start();
	}

	@After
	public void tearDown()
	{
		server.stop(0);
	}

	private void handleSync(HttpExchange exchange) throws IOException
	{
		lastRequestMethod = exchange.getRequestMethod();
		lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
		lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

		byte[] response = "{\"accepted\":true,\"totalEligibleSeconds\":0,\"auraPoints\":0}".getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(respondWithStatus, response.length);
		try (OutputStream os = exchange.getResponseBody())
		{
			os.write(response);
		}
		requestReceived.countDown();
	}

	private String baseUrl()
	{
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private void awaitRequest() throws InterruptedException
	{
		if (!requestReceived.await(5, TimeUnit.SECONDS))
		{
			fail("Server never received a request within the timeout");
		}
	}

	@Test
	public void sendsExpectedRequestShape() throws InterruptedException
	{
		AuraApiClient client = new AuraApiClient(baseUrl());

		client.syncAsync("a1b2c3d4-e5f6-4789-a012-3456789abcde", "TestSlayer42", 752_400);
		awaitRequest();

		assertEquals("POST", lastRequestMethod);
		assertEquals("application/json", lastContentType);
		assertTrue(lastRequestBody.contains("\"deviceId\":\"a1b2c3d4-e5f6-4789-a012-3456789abcde\""));
		assertTrue(lastRequestBody.contains("\"displayName\":\"TestSlayer42\""));
		assertTrue(lastRequestBody.contains("\"eligibleSecondsTotal\":752400"));
	}

	@Test
	public void neverThrowsWhenServerIsUnreachable()
	{
		// Nothing is listening on this port (server from setUp() is a different one) —
		// simulates the backend being down. Must not throw: a sync failure can never
		// surface to the caller.
		AuraApiClient client = new AuraApiClient("http://127.0.0.1:1");
		client.syncAsync("a1b2c3d4-e5f6-4789-a012-3456789abcde", "TestSlayer42", 60);
		// If we reach this line without an exception, the guarantee held.
	}

	@Test
	public void neverThrowsWhenServerReturnsAnErrorStatus() throws InterruptedException
	{
		respondWithStatus = 429;
		AuraApiClient client = new AuraApiClient(baseUrl());

		client.syncAsync("a1b2c3d4-e5f6-4789-a012-3456789abcde", "TestSlayer42", 60);
		awaitRequest();
		// No exception, no crash — the 429 is only ever logged (see AuraApiClient).
	}
}
