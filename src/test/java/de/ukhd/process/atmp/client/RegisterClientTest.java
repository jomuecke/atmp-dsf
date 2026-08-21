package de.ukhd.process.atmp.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

public class RegisterClientTest
{
	@Test
	public void testSendUsesExactConfiguredEndpointAndBuildsHeaderFromBhzAndRawKey() throws Exception
	{
		byte[] accepted = "{\"entries\":[{\"status\":\"stored\"}]}".getBytes(StandardCharsets.UTF_8);
		AtomicReference<String> path = new AtomicReference<>();
		AtomicReference<String> apiKeyHeader = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/medic-import", exchange ->
		{
			path.set(exchange.getRequestURI().getPath());
			apiKeyHeader.set(exchange.getRequestHeaders().getFirst(RegisterClient.API_KEY_HEADER));
			exchange.sendResponseHeaders(201, accepted.length);
			exchange.getResponseBody().write(accepted);
			exchange.close();
		});
		server.start();

		Path apiKeyFile = Files.createTempFile("atmp-register-raw-api-key", ".txt");
		try
		{
			Files.writeString(apiKeyFile, "  test-secret\n");
			RegisterClient client = new RegisterClient(
					"http://127.0.0.1:" + server.getAddress().getPort() + "/medic-import", "test-bhz",
					apiKeyFile.toString(), Duration.ofSeconds(2), Duration.ofSeconds(2));
			client.afterPropertiesSet();

			assertTrue(client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}").isFullyAccepted());
			assertEquals("/medic-import", path.get());
			assertEquals("eyJiaHoiOiJ0ZXN0LWJoeiIsImFwaUtleSI6InRlc3Qtc2VjcmV0In0=", apiKeyHeader.get());
		}
		finally
		{
			server.stop(0);
			Files.deleteIfExists(apiKeyFile);
		}
	}

	@Test
	public void testDizMultiStatusAckIsNotFullyAccepted() throws Exception
	{
		String response = """
				{"entries":[
				  {"status":"stored"},
				  {"status":"skipped","code":"DIZ-E101","reason":"unknown pseudonym"},
				  {"status":"rejected","code":"DIZ-E103","issues":[{"path":"entry.2","message":"invalid"}]}
				]}
				""";

		RegisterAck ack = RegisterAck.from(new ObjectMapper().readTree(response));

		assertFalse(ack.isFullyAccepted());
		assertEquals(1, ack.skippableFailures().size());
		assertEquals(0, ack.hardFailures().size());
		assertEquals(1, ack.parseIssues().size());
	}

	@Test
	public void testHttpMultiStatusWithoutUsableEntriesIsNotFullyAccepted() throws Exception
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/medic-import", exchange ->
		{
			exchange.sendResponseHeaders(207, -1);
			exchange.close();
		});
		server.start();

		Path apiKeyFile = Files.createTempFile("atmp-register-api-key", ".txt");
		try
		{
			Files.writeString(apiKeyFile, "test-key");
			RegisterClient client = new RegisterClient(
					"http://127.0.0.1:" + server.getAddress().getPort() + "/medic-import", apiKeyFile.toString(),
					Duration.ofSeconds(2), Duration.ofSeconds(2));
			client.afterPropertiesSet();

			RegisterAck ack = client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}");

			assertFalse(ack.isFullyAccepted());
			assertFalse(ack.parseIssues().isEmpty());
		}
		finally
		{
			server.stop(0);
			Files.deleteIfExists(apiKeyFile);
		}
	}

	@Test
	public void testSendUsesRequiredHttpContractWithoutCleartextHttp2Upgrade() throws Exception
	{
		byte[] accepted = "{\"success\":true,\"failures\":[],\"parseIssues\":[]}".getBytes(StandardCharsets.UTF_8);
		AtomicReference<String> method = new AtomicReference<>();
		AtomicReference<String> path = new AtomicReference<>();
		AtomicReference<String> contentType = new AtomicReference<>();
		AtomicReference<String> apiKey = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/medic-import", exchange ->
		{
			method.set(exchange.getRequestMethod());
			path.set(exchange.getRequestURI().getPath());
			contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			apiKey.set(exchange.getRequestHeaders().getFirst(RegisterClient.API_KEY_HEADER));
			boolean cleartextHttp2Upgrade = "h2c".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Upgrade"));
			byte[] response = cleartextHttp2Upgrade ? new byte[0] : accepted;
			exchange.sendResponseHeaders(cleartextHttp2Upgrade ? 404 : 201, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();

		Path apiKeyFile = Files.createTempFile("atmp-register-api-key", ".txt");
		try
		{
			Files.writeString(apiKeyFile, "  test-key\n");
			RegisterClient client = new RegisterClient(
					"http://127.0.0.1:" + server.getAddress().getPort() + "/api/medic-import", apiKeyFile.toString(),
					Duration.ofSeconds(2), Duration.ofSeconds(2));
			client.afterPropertiesSet();

			assertTrue(client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}").isFullyAccepted());
			assertEquals("POST", method.get());
			assertEquals("/api/medic-import", path.get());
			assertEquals("application/json", contentType.get());
			assertEquals("test-key", apiKey.get());
		}
		finally
		{
			server.stop(0);
			Files.deleteIfExists(apiKeyFile);
		}
	}
}
