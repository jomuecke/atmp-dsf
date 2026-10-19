package de.ukhd.process.atmp.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsServer;

import dev.dsf.bpe.v2.config.ProxyConfig;

public class RegisterClientTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();
	private final TestPki pki = new TestPki("Register");
	private SSLContext context;

	@Before
	public void setUp() throws Exception
	{
		Path directory = folder.getRoot().toPath();
		TestPki.Identity client = pki.client("atmp");
		context = new RegisterMtlsConfig(TestPki.writeChain(directory, client).toString(),
				TestPki.writeKey(directory, client.privateKey()).toString(), null,
				TestPki.writeCertificates(directory.resolve("ca.pem"), pki.caCertificate()).toString())
				.createSslContext(new TestCryptoService(), "https://localhost/medic-import");
	}

	@Test
	public void testProxyAuthenticationFailureAffectsWholeCycle() throws Exception
	{
		try (TestConnectProxy proxy = new TestConnectProxy();
				RegisterClientJersey client = proxyClient(proxy, "wrong-password"))
		{
			RegisterClient.RegisterCycleException exception = assertThrows(RegisterClient.RegisterCycleException.class,
					() -> client.send("{}"));
			assertTrue(exception.getMessage(), exception.getMessage().contains("407"));
			assertNull(proxy.failure.get());
		}
	}

	@Test
	public void testSendUsesAuthenticatedDsfProxy() throws Exception
	{
		try (TestConnectProxy proxy = new TestConnectProxy();
				RegisterClientJersey client = proxyClient(proxy, "proxy-password"))
		{
			assertThrows(RegisterClient.RegisterCycleException.class, () -> client.send("{}"));
			assertEquals("CONNECT register.invalid:443 HTTP/1.1", proxy.requestLine.get());
			assertEquals(
					"Basic " + Base64.getEncoder()
							.encodeToString("proxy-user:proxy-password".getBytes(StandardCharsets.ISO_8859_1)),
					proxy.authorization.get());
			assertTrue("CONNECT must retry after the proxy's challenge", proxy.requests.get() >= 2);
			assertNull(proxy.failure.get());
		}
	}

	private RegisterClientJersey proxyClient(TestConnectProxy proxy, String password) throws Exception
	{
		Path apiKey = Files.writeString(folder.getRoot().toPath().resolve("api-key"), "test-key");
		return new RegisterClientJersey("https://register.invalid/medic-import", null, apiKey.toString(),
				Duration.ofSeconds(2), Duration.ofSeconds(2), proxyConfig(proxy, true, password), context);
	}

	@Test
	public void testSendBypassesProxyForDsfNoProxyDestination() throws Exception
	{
		HttpsServer target = TestRegisterServer.create(pki.server("localhost"), pki.caCertificate());
		AtomicInteger targetRequests = new AtomicInteger();
		target.createContext("/medic-import", exchange ->
		{
			targetRequests.incrementAndGet();
			exchange.sendResponseHeaders(201, -1);
			exchange.close();
		});
		target.start();
		try (TestConnectProxy proxy = new TestConnectProxy())
		{
			Path apiKey = Files.writeString(folder.getRoot().toPath().resolve("api-key"), "test-key");
			try (RegisterClientJersey client = new RegisterClientJersey(
					"https://localhost:" + target.getAddress().getPort() + "/medic-import", null, apiKey.toString(),
					Duration.ofSeconds(2), Duration.ofSeconds(2), proxyConfig(proxy, false, "proxy-password"), context))
			{
				assertTrue(client.send("{}").isFullyAccepted());
				assertEquals(1, targetRequests.get());
				assertEquals("DSF no-proxy decision must be respected", 0, proxy.requests.get());
			}
		}
		finally
		{
			target.stop(0);
		}
	}

	private ProxyConfig proxyConfig(TestConnectProxy proxy, boolean enabledForTarget, String password)
	{
		return new ProxyConfig()
		{
			@Override
			public String getUrl()
			{
				return proxy == null ? null : "http://127.0.0.1:" + proxy.port();
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
				return password == null ? null : "proxy-user";
			}

			@Override
			public char[] getPassword()
			{
				return password == null ? null : password.toCharArray();
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

	private ProxyConfig directConnection()
	{
		return proxyConfig(null, false, null);
	}

	@Test
	public void testSendUsesExactConfiguredEndpointAndBuildsHeaderFromBhzAndRawKey() throws Exception
	{
		byte[] accepted = "{\"entries\":[{\"status\":\"stored\"}]}".getBytes(StandardCharsets.UTF_8);
		AtomicReference<String> path = new AtomicReference<>();
		AtomicReference<String> apiKeyHeader = new AtomicReference<>();
		HttpsServer server = TestRegisterServer.create(pki.server("localhost"), pki.caCertificate());
		server.createContext("/medic-import", exchange ->
		{
			path.set(exchange.getRequestURI().getPath());
			apiKeyHeader.set(exchange.getRequestHeaders().getFirst(RegisterClientJersey.API_KEY_HEADER));
			exchange.sendResponseHeaders(201, accepted.length);
			exchange.getResponseBody().write(accepted);
			exchange.close();
		});
		server.start();

		Path apiKeyFile = Files.createTempFile("atmp-register-raw-api-key", ".txt");
		try
		{
			Files.writeString(apiKeyFile, "  test-secret\n");
			try (RegisterClientJersey client = new RegisterClientJersey(
					"https://localhost:" + server.getAddress().getPort() + "/medic-import", "test-bhz",
					apiKeyFile.toString(), Duration.ofSeconds(2), Duration.ofSeconds(2), directConnection(), context))
			{
				assertTrue(client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}").isFullyAccepted());
				assertEquals("/medic-import", path.get());
				assertEquals("eyJiaHoiOiJ0ZXN0LWJoeiIsImFwaUtleSI6InRlc3Qtc2VjcmV0In0=", apiKeyHeader.get());
			}
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
		HttpsServer server = TestRegisterServer.create(pki.server("localhost"), pki.caCertificate());
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
			try (RegisterClientJersey client = new RegisterClientJersey(
					"https://localhost:" + server.getAddress().getPort() + "/medic-import", null, apiKeyFile.toString(),
					Duration.ofSeconds(2), Duration.ofSeconds(2), directConnection(), context))
			{
				RegisterAck ack = client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}");

				assertFalse(ack.isFullyAccepted());
				assertFalse(ack.parseIssues().isEmpty());
			}
		}
		finally
		{
			server.stop(0);
			Files.deleteIfExists(apiKeyFile);
		}
	}

	@Test
	public void testForbiddenAffectsWholeCycle() throws Exception
	{
		HttpsServer server = TestRegisterServer.create(pki.server("localhost"), pki.caCertificate());
		server.createContext("/medic-import", exchange ->
		{
			byte[] body = "forbidden".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(403, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();

		Path apiKeyFile = Files.createTempFile("atmp-register-forbidden", ".txt");
		try
		{
			Files.writeString(apiKeyFile, "test-key");
			try (RegisterClientJersey client = new RegisterClientJersey(
					"https://localhost:" + server.getAddress().getPort() + "/medic-import", null, apiKeyFile.toString(),
					Duration.ofSeconds(2), Duration.ofSeconds(2), directConnection(), context))
			{
				try
				{
					client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}");
					fail("expected a register-wide authorization failure");
				}
				catch (RegisterClient.RegisterCycleException exception)
				{
					assertTrue(exception.getMessage().contains("status 403"));
				}
			}
		}
		finally
		{
			server.stop(0);
			Files.deleteIfExists(apiKeyFile);
		}
	}

	@Test
	public void testRedirectIsNotFollowedAndAffectsWholeCycle() throws Exception
	{
		AtomicInteger redirectedRequests = new AtomicInteger();
		HttpsServer server = TestRegisterServer.create(pki.server("localhost"), pki.caCertificate());
		server.createContext("/medic-import", exchange ->
		{
			exchange.getResponseHeaders().add("Location", "/elsewhere");
			exchange.sendResponseHeaders(303, -1);
			exchange.close();
		});
		server.createContext("/elsewhere", exchange ->
		{
			redirectedRequests.incrementAndGet();
			exchange.sendResponseHeaders(201, -1);
			exchange.close();
		});
		server.start();

		Path apiKeyFile = Files.createTempFile("atmp-register-redirect", ".txt");
		try
		{
			Files.writeString(apiKeyFile, "test-key");
			try (RegisterClientJersey client = new RegisterClientJersey(
					"https://localhost:" + server.getAddress().getPort() + "/medic-import", null, apiKeyFile.toString(),
					Duration.ofSeconds(2), Duration.ofSeconds(2), directConnection(), context))
			{
				try
				{
					client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}");
					fail("expected a redirect to fail the cycle");
				}
				catch (RegisterClient.RegisterCycleException exception)
				{
					assertTrue(exception.getMessage().contains("status 303"));
				}
				assertEquals(0, redirectedRequests.get());
			}
		}
		finally
		{
			server.stop(0);
			Files.deleteIfExists(apiKeyFile);
		}
	}

	@Test
	public void testSendUsesRequiredHttpContract() throws Exception
	{
		byte[] accepted = "{\"success\":true,\"failures\":[],\"parseIssues\":[]}".getBytes(StandardCharsets.UTF_8);
		AtomicReference<String> protocol = new AtomicReference<>();
		AtomicReference<String> upgrade = new AtomicReference<>();
		AtomicReference<String> http2Settings = new AtomicReference<>();
		AtomicReference<String> contentLength = new AtomicReference<>();
		AtomicReference<String> transferEncoding = new AtomicReference<>();
		AtomicReference<String> body = new AtomicReference<>();
		AtomicReference<String> method = new AtomicReference<>();
		AtomicReference<String> path = new AtomicReference<>();
		AtomicReference<String> contentType = new AtomicReference<>();
		AtomicReference<String> apiKey = new AtomicReference<>();
		HttpsServer server = TestRegisterServer.create(pki.server("localhost"), pki.caCertificate());
		server.createContext("/api/medic-import", exchange ->
		{
			method.set(exchange.getRequestMethod());
			path.set(exchange.getRequestURI().getPath());
			contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			apiKey.set(exchange.getRequestHeaders().getFirst(RegisterClientJersey.API_KEY_HEADER));
			protocol.set(exchange.getProtocol());
			upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
			http2Settings.set(exchange.getRequestHeaders().getFirst("HTTP2-Settings"));
			contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
			transferEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.sendResponseHeaders(201, accepted.length);
			exchange.getResponseBody().write(accepted);
			exchange.close();
		});
		server.start();

		Path apiKeyFile = Files.createTempFile("atmp-register-api-key", ".txt");
		try
		{
			Files.writeString(apiKeyFile, "  test-key\n");
			try (RegisterClientJersey client = new RegisterClientJersey(
					"https://localhost:" + server.getAddress().getPort() + "/api/medic-import", null,
					apiKeyFile.toString(), Duration.ofSeconds(2), Duration.ofSeconds(2), directConnection(), context))
			{
				assertTrue(client.send("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}").isFullyAccepted());
				assertEquals("HTTP/1.1", protocol.get());
				assertNull(upgrade.get());
				assertNull(http2Settings.get());
				assertNull(transferEncoding.get());
				assertEquals("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}", body.get());
				assertEquals("45", contentLength.get());
				assertEquals("POST", method.get());
				assertEquals("/api/medic-import", path.get());
				assertEquals("application/json", contentType.get());
				assertEquals("test-key", apiKey.get());
			}
		}
		finally
		{
			server.stop(0);
			Files.deleteIfExists(apiKeyFile);
		}
	}
}
