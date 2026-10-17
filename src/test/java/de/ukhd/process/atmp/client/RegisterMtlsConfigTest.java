package de.ukhd.process.atmp.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import de.ukhd.process.atmp.client.TestPki.Identity;

/**
 * Startup validation of the register mTLS configuration: misconfiguration fails before the first transfer; certificate
 * validity only warns, the register enforces it per cycle.
 */
public class RegisterMtlsConfigTest
{
	private static final String ENDPOINT = "https://register.example/api/medic-import";

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final TestCryptoService crypto = new TestCryptoService();
	private final TestPki pki = new TestPki("ATMP Client");

	private Path directory;
	private String chain;
	private String key;
	private String serverCas;

	@Before
	public void setUp() throws IOException
	{
		directory = folder.getRoot().toPath();
		Identity client = pki.client("dic1-atmp");
		chain = TestPki.writeChain(directory, client).toString();
		key = TestPki.writeKey(directory, client.privateKey()).toString();
		serverCas = TestPki.writeCertificates(directory.resolve("server-cas.pem"), pki.caCertificate()).toString();
	}

	@Test
	public void testDisabledWithoutCredentialsKeepsDefaultTls()
	{
		assertFalse(
				new RegisterMtlsConfig(false, null, " ", null, null).createSslContext(crypto, ENDPOINT).isPresent());
	}

	@Test
	public void testDisabledWithCredentialsIsRejected()
	{
		assertInvalid(new RegisterMtlsConfig(false, chain, null, null, null), ENDPOINT);
		assertInvalid(new RegisterMtlsConfig(false, null, null, null, serverCas), ENDPOINT);
	}

	@Test
	public void testEnabledRequiresHttpsEndpointWithHost()
	{
		RegisterMtlsConfig config = new RegisterMtlsConfig(true, chain, key, null, serverCas);

		assertTrue(config.createSslContext(crypto, ENDPOINT).isPresent());
		assertInvalid(config, "http://register.example/api/medic-import");
		assertInvalid(config, "https:///api/medic-import");
		assertInvalid(config, "/api/medic-import");
	}

	@Test
	public void testEnabledRequiresChainKeyAndServerCas()
	{
		assertInvalid(new RegisterMtlsConfig(true, null, key, null, serverCas), ENDPOINT);
		assertInvalid(new RegisterMtlsConfig(true, chain, " ", null, serverCas), ENDPOINT);
		assertInvalid(new RegisterMtlsConfig(true, chain, key, null, null), ENDPOINT);
		assertInvalid(new RegisterMtlsConfig(true, directory.resolve("missing.pem").toString(), key, null, serverCas),
				ENDPOINT);
	}

	@Test
	public void testEmptyChainOrServerCaFileIsRejected() throws IOException
	{
		String empty = Files.writeString(directory.resolve("empty.pem"), "").toString();

		assertInvalid(new RegisterMtlsConfig(true, empty, key, null, serverCas), ENDPOINT);
		assertInvalid(new RegisterMtlsConfig(true, chain, key, null, empty), ENDPOINT);
	}

	@Test
	public void testKeyNotMatchingClientCertificateIsRejected() throws IOException
	{
		String otherKey = TestPki.writeKey(folder.newFolder().toPath(), pki.client("other").privateKey()).toString();

		assertInvalid(new RegisterMtlsConfig(true, chain, otherKey, null, serverCas), ENDPOINT);
	}

	@Test
	public void testExpiredClientCertificateLoadsSoTheRegisterRejectsCyclesInsteadOfStartup() throws IOException
	{
		Identity expired = pki.client("expired", Duration.ofDays(-1));
		Path expiredDirectory = folder.newFolder().toPath();

		assertTrue(new RegisterMtlsConfig(true, TestPki.writeChain(expiredDirectory, expired).toString(),
				TestPki.writeKey(expiredDirectory, expired.privateKey()).toString(), null, serverCas)
				.createSslContext(crypto, ENDPOINT).isPresent());
	}

	@Test
	public void testServerOnlyCertificateIsRejectedAsClientIdentity() throws IOException
	{
		Identity server = pki.server("register.example");
		Path serverDirectory = folder.newFolder().toPath();

		assertInvalid(new RegisterMtlsConfig(true, TestPki.writeChain(serverDirectory, server).toString(),
				TestPki.writeKey(serverDirectory, server.privateKey()).toString(), null, serverCas), ENDPOINT);
	}

	@Test
	public void testCaCertificateIsRejectedAsClientLeaf() throws IOException
	{
		String caAsLeaf = TestPki.writeCertificates(directory.resolve("ca-leaf.pem"), pki.caCertificate()).toString();
		String caKey = TestPki.writeKey(folder.newFolder().toPath(), pki.caPrivateKey()).toString();

		assertInvalid(new RegisterMtlsConfig(true, caAsLeaf, caKey, null, serverCas), ENDPOINT);
	}

	@Test
	public void testEncryptedKeyIsReadWithPasswordFileWithoutTerminalLineEnding() throws IOException
	{
		Identity client = pki.client("encrypted");
		Path encryptedDirectory = folder.newFolder().toPath();
		String encryptedChain = TestPki.writeChain(encryptedDirectory, client).toString();
		String encryptedKey = TestPki
				.writeEncryptedKey(encryptedDirectory, client.privateKey(), " pass word ".toCharArray()).toString();
		String password = Files.writeString(directory.resolve("password"), " pass word \n").toString();
		String wrongPassword = Files.writeString(directory.resolve("wrong-password"), "pass word\n").toString();

		assertTrue(new RegisterMtlsConfig(true, encryptedChain, encryptedKey, password, serverCas)
				.createSslContext(crypto, ENDPOINT).isPresent());
		assertInvalid(new RegisterMtlsConfig(true, encryptedChain, encryptedKey, wrongPassword, serverCas), ENDPOINT);
	}

	private void assertInvalid(RegisterMtlsConfig config, String endpoint)
	{
		assertThrows(IllegalArgumentException.class, () -> config.createSslContext(crypto, endpoint));
	}
}
