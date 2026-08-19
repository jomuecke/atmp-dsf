package de.ukhd.process.atmp.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

public class RegisterClientTest
{
	@Test
	public void testSendUsesRequiredHttpContractWithoutCleartextHttp2Upgrade() throws Exception
	{
		byte[] accepted = "{\"success\":true,\"failures\":[],\"parseIssues\":[]}".getBytes(StandardCharsets.UTF_8);
		AtomicReference<String> method = new AtomicReference<>();
		AtomicReference<String> path = new AtomicReference<>();
		AtomicReference<String> contentType = new AtomicReference<>();
		AtomicReference<String> apiKey = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(RegisterClient.IMPORT_PATH, exchange ->
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
			RegisterClient client = new RegisterClient("http://127.0.0.1:" + server.getAddress().getPort(),
					apiKeyFile.toString(), Duration.ofSeconds(2), Duration.ofSeconds(2));
			client.afterPropertiesSet();

			assertTrue(client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}").isFullyAccepted());
			assertEquals("POST", method.get());
			assertEquals(RegisterClient.IMPORT_PATH, path.get());
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
