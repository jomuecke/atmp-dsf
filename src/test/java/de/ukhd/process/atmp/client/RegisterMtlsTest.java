package de.ukhd.process.atmp.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;

import de.ukhd.process.atmp.client.TestPki.Identity;
import dev.dsf.bpe.v2.config.ProxyConfig;

/**
 * Register transport over mutual TLS: a real {@link RegisterClientJersey} configured through {@link RegisterMtlsConfig}
 * against an in-process HTTPS server that requires client certificates.
 */
public class RegisterMtlsTest
{
	private static final String BUNDLE = "{\"resourceType\":\"Bundle\",\"type\":\"collection\"}";
	private static final byte[] ACCEPTED = "{\"entries\":[{\"status\":\"stored\"}]}".getBytes(StandardCharsets.UTF_8);

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final TestCryptoService crypto = new TestCryptoService();
	private final TestPki serverPki = new TestPki("Register Server");
	private final TestPki clientPki = new TestPki("ATMP Client");

	private final AtomicInteger imports = new AtomicInteger();
	private final AtomicReference<String> receivedApiKey = new AtomicReference<>();
	private final AtomicReference<String> receivedClientSubject = new AtomicReference<>();

	private HttpsServer register;
	private Path apiKeyFile;
	private Path trustedServerCas;

	@Before
	public void setUp() throws Exception
	{
		apiKeyFile = folder.getRoot().toPath().resolve("api-key");
		Files.writeString(apiKeyFile, "raw-key");
		trustedServerCas = TestPki.writeCertificates(folder.getRoot().toPath().resolve("server-cas.pem"),
				serverPki.caCertificate());
	}

	@After
	public void tearDown()
	{
		if (register != null)
			register.stop(0);
	}

	@Test
	public void testPermittedClientCertificateDeliversBundleWithApiKey() throws Exception
	{
		startRegister(serverPki.server("localhost"), clientPki.caCertificate());
		Identity client = clientPki.client("dic1-atmp");

		try (RegisterClientJersey registerClient = client(mtls(client)))
		{
			assertTrue(registerClient.send(BUNDLE).isFullyAccepted());
		}

		assertEquals(1, imports.get());
		assertEquals(client.certificate().getSubjectX500Principal().getName(), receivedClientSubject.get());
		assertEquals(expectedApiKeyHeader(), receivedApiKey.get());
	}

	@Test
	public void testUntrustedServerCertificateFailsCycleBeforeImport() throws Exception
	{
		startRegister(new TestPki("Unknown Server").server("localhost"), clientPki.caCertificate());

		assertCycleFailure(clientPki.client("dic1-atmp"), "server certificate");
	}

	@Test
	public void testServerHostnameMismatchFailsCycleBeforeImport() throws Exception
	{
		startRegister(serverPki.server("other-register.example"), clientPki.caCertificate());

		assertCycleFailure(clientPki.client("dic1-atmp"), "server certificate");
	}

	@Test
	public void testExpiredServerCertificateFailsCycleBeforeImport() throws Exception
	{
		startRegister(serverPki.server(Duration.ofDays(-1), "localhost"), clientPki.caCertificate());

		assertCycleFailure(clientPki.client("dic1-atmp"), "server certificate");
	}

	@Test
	public void testClientCertificateNotTrustedByRegisterFailsCycleBeforeImport() throws Exception
	{
		startRegister(serverPki.server("localhost"), clientPki.caCertificate());

		// depending on TLS version the rejection surfaces as handshake alert or as a connection closed mid-request
		assertCycleFailure(new TestPki("Unknown Client").client("dic1-atmp"), "client certificate");
	}

	@Test
	public void testExpiredClientCertificateFailsCycleBeforeImport() throws Exception
	{
		startRegister(serverPki.server("localhost"), clientPki.caCertificate());

		assertCycleFailure(clientPki.client("dic1-atmp", Duration.ofDays(-1)), "client certificate");
	}

	private void assertCycleFailure(Identity client, String expectedDiagnostic) throws IOException
	{
		try (RegisterClientJersey registerClient = client(mtls(client)))
		{
			registerClient.send(BUNDLE);
			fail("expected a register cycle failure");
		}
		catch (RegisterClient.RegisterCycleException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains(expectedDiagnostic));
			assertEquals(0, imports.get());
		}
	}

	private RegisterMtlsConfig mtls(Identity client) throws IOException
	{
		Path directory = folder.newFolder().toPath();
		return new RegisterMtlsConfig(TestPki.writeChain(directory, client).toString(),
				TestPki.writeKey(directory, client.privateKey()).toString(), null, trustedServerCas.toString());
	}

	private RegisterClientJersey client(RegisterMtlsConfig mtls) throws IOException
	{
		String endpoint = endpoint("localhost");
		return new RegisterClientJersey(endpoint, "bhz-demo", apiKeyFile.toString(), Duration.ofSeconds(5),
				Duration.ofSeconds(5), noProxy(), mtls.createSslContext(crypto, endpoint));
	}

	private String endpoint(String host)
	{
		return "https://" + host + ":" + register.getAddress().getPort() + "/api/medic-import";
	}

	private static String expectedApiKeyHeader()
	{
		return Base64.getEncoder()
				.encodeToString("{\"bhz\":\"bhz-demo\",\"apiKey\":\"raw-key\"}".getBytes(StandardCharsets.UTF_8));
	}

	private void startRegister(Identity server, X509Certificate trustedClientCa) throws Exception
	{
		register = TestRegisterServer.create(server, trustedClientCa);
		register.createContext("/api/medic-import", exchange ->
		{
			imports.incrementAndGet();
			receivedApiKey.set(exchange.getRequestHeaders().getFirst(RegisterClientJersey.API_KEY_HEADER));
			receivedClientSubject
					.set(((X509Certificate) ((HttpsExchange) exchange).getSSLSession().getPeerCertificates()[0])
							.getSubjectX500Principal().getName());
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(201, ACCEPTED.length);
			exchange.getResponseBody().write(ACCEPTED);
			exchange.close();
		});
		register.start();
	}

	private static ProxyConfig noProxy()
	{
		return new ProxyConfig()
		{
			@Override
			public String getUrl()
			{
				return null;
			}

			@Override
			public boolean isEnabled()
			{
				return false;
			}

			@Override
			public boolean isEnabled(String targetUrl)
			{
				return false;
			}

			@Override
			public String getUsername()
			{
				return null;
			}

			@Override
			public char[] getPassword()
			{
				return null;
			}

			@Override
			public List<String> getNoProxyUrls()
			{
				return List.of();
			}

			@Override
			public boolean isNoProxyUrl(String targetUrl)
			{
				return true;
			}
		};
	}
}
