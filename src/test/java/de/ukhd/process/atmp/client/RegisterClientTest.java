package de.ukhd.process.atmp.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import dev.dsf.bpe.v2.config.ProxyConfig;

public class RegisterClientTest
{
	@Test
	public void testProxyAuthenticationFailureAffectsWholeCycle() throws Exception
	{
		HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		proxy.createContext("/", exchange ->
		{
			exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"atmp-test\"");
			exchange.sendResponseHeaders(407, -1);
			exchange.close();
		});
		proxy.start();

		Path apiKeyFile = Files.createTempFile("atmp-register-proxy-auth-failure", ".txt");
		RegisterClient client = null;
		try
		{
			Files.writeString(apiKeyFile, "test-key");
			client = new RegisterClient("http://register.invalid/medic-import", apiKeyFile.toString(),
					Duration.ofSeconds(2), Duration.ofSeconds(2), proxyConfig(proxy, true, "wrong-password"));
			client.afterPropertiesSet();

			try
			{
				client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}");
				fail("expected a proxy authentication failure");
			}
			catch (RegisterClient.RegisterProxyAuthException exception)
			{
				assertTrue(exception.affectsWholeCycle());
			}
		}
		finally
		{
			close(client);
			proxy.stop(0);
			Files.deleteIfExists(apiKeyFile);
		}
	}

	@Test
	public void testSendUsesAuthenticatedDsfProxy() throws Exception
	{
		byte[] accepted = "{\"entries\":[{\"status\":\"stored\"}]}".getBytes(StandardCharsets.UTF_8);
		String expectedProxyAuthorization = "Basic "
				+ Base64.getEncoder().encodeToString("proxy-user:proxy-password".getBytes(StandardCharsets.ISO_8859_1));
		AtomicInteger requests = new AtomicInteger();
		AtomicReference<String> proxyAuthorization = new AtomicReference<>();
		AtomicReference<String> requestedUri = new AtomicReference<>();
		HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		proxy.createContext("/", exchange ->
		{
			requests.incrementAndGet();
			requestedUri.set(exchange.getRequestURI().toString());
			String authorization = exchange.getRequestHeaders().getFirst("Proxy-Authorization");
			if (!expectedProxyAuthorization.equals(authorization))
			{
				exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"atmp-test\"");
				exchange.sendResponseHeaders(407, -1);
				exchange.close();
				return;
			}

			proxyAuthorization.set(authorization);
			exchange.sendResponseHeaders(201, accepted.length);
			exchange.getResponseBody().write(accepted);
			exchange.close();
		});
		proxy.start();

		Path apiKeyFile = Files.createTempFile("atmp-register-proxy-api-key", ".txt");
		RegisterClient client = null;
		try
		{
			Files.writeString(apiKeyFile, "test-key");
			client = new RegisterClient("http://register.invalid/medic-import", apiKeyFile.toString(),
					Duration.ofSeconds(2), Duration.ofSeconds(2), proxyConfig(proxy, true, "proxy-password"));
			client.afterPropertiesSet();

			assertTrue(client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}").isFullyAccepted());
			assertTrue("proxy should receive the unauthenticated challenge and authenticated retry",
					requests.get() >= 2);
			assertEquals(expectedProxyAuthorization, proxyAuthorization.get());
			assertEquals("http://register.invalid/medic-import", requestedUri.get());
		}
		finally
		{
			close(client);
			proxy.stop(0);
			Files.deleteIfExists(apiKeyFile);
		}
	}

	@Test
	public void testSendBypassesProxyForDsfNoProxyDestination() throws Exception
	{
		byte[] accepted = "{\"entries\":[{\"status\":\"stored\"}]}".getBytes(StandardCharsets.UTF_8);
		AtomicInteger targetRequests = new AtomicInteger();
		HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		target.createContext("/medic-import", exchange ->
		{
			targetRequests.incrementAndGet();
			exchange.sendResponseHeaders(201, accepted.length);
			exchange.getResponseBody().write(accepted);
			exchange.close();
		});
		target.start();

		AtomicInteger proxyRequests = new AtomicInteger();
		HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		proxy.createContext("/", exchange ->
		{
			proxyRequests.incrementAndGet();
			exchange.sendResponseHeaders(502, -1);
			exchange.close();
		});
		proxy.start();

		Path apiKeyFile = Files.createTempFile("atmp-register-no-proxy-api-key", ".txt");
		RegisterClient client = null;
		try
		{
			Files.writeString(apiKeyFile, "test-key");
			String endpoint = "http://127.0.0.1:" + target.getAddress().getPort() + "/medic-import";
			client = new RegisterClient(endpoint, apiKeyFile.toString(), Duration.ofSeconds(2), Duration.ofSeconds(2),
					proxyConfig(proxy, false, "proxy-password"));
			client.afterPropertiesSet();

			assertTrue(client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}").isFullyAccepted());
			assertEquals(1, targetRequests.get());
			assertEquals("DSF no-proxy decision must be respected", 0, proxyRequests.get());
		}
		finally
		{
			close(client);
			proxy.stop(0);
			target.stop(0);
			Files.deleteIfExists(apiKeyFile);
		}
	}

	private ProxyConfig proxyConfig(HttpServer proxy, boolean enabledForTarget, String password)
	{
		return new ProxyConfig()
		{
			@Override
			public String getUrl()
			{
				return "http://127.0.0.1:" + proxy.getAddress().getPort();
			}

			@Override
			public boolean isEnabled()
			{
				return true;
			}

			@Override
			public boolean isEnabled(String targetUrl)
			{
				return enabledForTarget;
			}

			@Override
			public String getUsername()
			{
				return "proxy-user";
			}

			@Override
			public char[] getPassword()
			{
				return password.toCharArray();
			}

			@Override
			public List<String> getNoProxyUrls()
			{
				return enabledForTarget ? List.of() : List.of("127.0.0.1");
			}

			@Override
			public boolean isNoProxyUrl(String url)
			{
				return !enabledForTarget;
			}
		};
	}

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
		RegisterClient client = null;
		try
		{
			Files.writeString(apiKeyFile, "  test-secret\n");
			client = new RegisterClient("http://127.0.0.1:" + server.getAddress().getPort() + "/medic-import",
					"test-bhz", apiKeyFile.toString(), Duration.ofSeconds(2), Duration.ofSeconds(2));
			client.afterPropertiesSet();

			assertTrue(client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}").isFullyAccepted());
			assertEquals("/medic-import", path.get());
			assertEquals("eyJiaHoiOiJ0ZXN0LWJoeiIsImFwaUtleSI6InRlc3Qtc2VjcmV0In0=", apiKeyHeader.get());
		}
		finally
		{
			close(client);
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
		RegisterClient client = null;
		try
		{
			Files.writeString(apiKeyFile, "test-key");
			client = new RegisterClient("http://127.0.0.1:" + server.getAddress().getPort() + "/medic-import",
					apiKeyFile.toString(), Duration.ofSeconds(2), Duration.ofSeconds(2));
			client.afterPropertiesSet();

			RegisterAck ack = client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}");

			assertFalse(ack.isFullyAccepted());
			assertFalse(ack.parseIssues().isEmpty());
		}
		finally
		{
			close(client);
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
		RegisterClient client = null;
		try
		{
			Files.writeString(apiKeyFile, "  test-key\n");
			client = new RegisterClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api/medic-import",
					apiKeyFile.toString(), Duration.ofSeconds(2), Duration.ofSeconds(2));
			client.afterPropertiesSet();

			assertTrue(client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}").isFullyAccepted());
			assertEquals("POST", method.get());
			assertEquals("/api/medic-import", path.get());
			assertEquals("application/json", contentType.get());
			assertEquals("test-key", apiKey.get());
		}
		finally
		{
			close(client);
			server.stop(0);
			Files.deleteIfExists(apiKeyFile);
		}
	}

	private void close(RegisterClient client) throws Exception
	{
		if (client != null)
			client.destroy();
	}
}
