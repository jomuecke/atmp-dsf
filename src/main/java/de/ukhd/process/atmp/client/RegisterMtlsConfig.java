package de.ukhd.process.atmp.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.dsf.bpe.v2.service.CryptoService;

/**
 * Mandatory client-certificate (mTLS) configuration of the register connection. The certificate is an additional
 * transport gate; the {@code MEDIC-API-KEY} header is sent unchanged on every request.
 *
 * @param certificateChainFile
 *            PEM file with the client leaf certificate followed by its intermediates
 * @param privateKeyFile
 *            PEM file with the client private key
 * @param privateKeyPasswordFile
 *            secret file with the password of an encrypted private key; one terminal line ending is ignored
 * @param trustedServerCasFile
 *            PEM file with the CA certificates trusted for the register server
 */
public record RegisterMtlsConfig(String certificateChainFile, String privateKeyFile, String privateKeyPasswordFile,
		String trustedServerCasFile)
{
	public static final String PROPERTY_PREFIX = "de.ukhd.atmp.api.mtls.";

	private static final Logger logger = LoggerFactory.getLogger(RegisterMtlsConfig.class);
	private static final Duration EXPIRY_WARNING = Duration.ofDays(30);

	/**
	 * Validates the configuration and the credential files. Messages name properties and files, never their content.
	 *
	 * @param crypto
	 *            DSF crypto service, not {@code null}
	 * @param importEndpointUrl
	 *            configured register endpoint
	 * @return the SSLContext presenting the configured client identity and trusting only the configured server CAs
	 * @throws IllegalArgumentException
	 *             if the configuration is incomplete, inconsistent or the credential files are unusable
	 */
	public SSLContext createSslContext(CryptoService crypto, String importEndpointUrl)
	{
		requireHttpsEndpoint(importEndpointUrl);
		Path chainFile = requiredFile(certificateChainFile, "certificate.chain.file");
		Path keyFile = requiredFile(privateKeyFile, "private.key.file");
		Path passwordFile = isSet(privateKeyPasswordFile)
				? requiredFile(privateKeyPasswordFile, "private.key.password.file")
				: null;
		Path serverCasFile = requiredFile(trustedServerCasFile, "trusted.server.cas.file");

		List<X509Certificate> chain = readCertificates(crypto, chainFile, "client certificate chain");
		PrivateKey key = readPrivateKey(crypto, keyFile, passwordFile);
		List<X509Certificate> trustedServerCas = readCertificates(crypto, serverCasFile, "trusted server CA");

		validateClientIdentity(crypto, chain, key, chainFile, keyFile);

		char[] keyStorePassword = UUID.randomUUID().toString().toCharArray();
		try
		{
			KeyStore keyStore = crypto.createKeyStoreForPrivateKeyAndCertificateChain(key, keyStorePassword, chain);
			KeyStore trustStore = crypto.createKeyStoreForTrustedCertificates(trustedServerCas);
			return crypto.createSSLContext(trustStore, keyStore, keyStorePassword);
		}
		catch (GeneralSecurityException | RuntimeException exception)
		{
			throw new IllegalArgumentException(
					"Could not create register mTLS context: " + exception.getClass().getSimpleName(), exception);
		}
	}

	private static boolean isSet(String value)
	{
		return value != null && !value.isBlank();
	}

	private static void requireHttpsEndpoint(String importEndpointUrl)
	{
		URI endpoint;
		try
		{
			endpoint = new URI(importEndpointUrl == null ? "" : importEndpointUrl.trim());
		}
		catch (URISyntaxException exception)
		{
			throw new IllegalArgumentException("Register mTLS requires a valid https URL in de.ukhd.atmp.api.url",
					exception);
		}

		if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null)
			throw new IllegalArgumentException(
					"Register mTLS requires an absolute https URL with a hostname in de.ukhd.atmp.api.url, got '"
							+ endpoint + "'");
	}

	private static Path requiredFile(String value, String property)
	{
		if (!isSet(value))
			throw new IllegalArgumentException(PROPERTY_PREFIX + property + " is required");

		Path file = Path.of(value.trim());
		if (!Files.isRegularFile(file) || !Files.isReadable(file))
			throw new IllegalArgumentException(
					PROPERTY_PREFIX + property + " file '" + file + "' does not exist or is not readable");

		return file;
	}

	private static List<X509Certificate> readCertificates(CryptoService crypto, Path file, String description)
	{
		List<X509Certificate> certificates;
		try
		{
			certificates = crypto.readCertificates(file);
		}
		catch (IOException | RuntimeException exception)
		{
			throw new IllegalArgumentException("Could not read " + description + " PEM file '" + file + "': "
					+ exception.getClass().getSimpleName(), exception);
		}

		if (certificates == null || certificates.isEmpty())
			throw new IllegalArgumentException(description + " PEM file '" + file + "' contains no certificate");

		return certificates;
	}

	private static PrivateKey readPrivateKey(CryptoService crypto, Path keyFile, Path passwordFile)
	{
		char[] password = passwordFile == null ? null : readPassword(passwordFile);
		PrivateKey key;
		try
		{
			key = crypto.readPrivateKey(keyFile, password);
		}
		catch (IOException | RuntimeException exception)
		{
			// parser messages are not forwarded, only the exception type: they may describe the secret material
			throw new IllegalArgumentException("Could not read private key PEM file '" + keyFile + "'"
					+ (passwordFile == null
							? "; an encrypted key needs " + PROPERTY_PREFIX + "private.key.password.file"
							: " with the configured password")
					+ ": " + exception.getClass().getSimpleName());
		}

		if (key == null)
			throw new IllegalArgumentException("Private key PEM file '" + keyFile + "' contains no private key");

		return key;
	}

	private static char[] readPassword(Path passwordFile)
	{
		try
		{
			String password = Files.readString(passwordFile, StandardCharsets.UTF_8);
			if (password.endsWith("\r\n"))
				password = password.substring(0, password.length() - 2);
			else if (password.endsWith("\n"))
				password = password.substring(0, password.length() - 1);

			if (password.isEmpty())
				throw new IllegalArgumentException(
						PROPERTY_PREFIX + "private.key.password.file '" + passwordFile + "' is empty");

			return password.toCharArray();
		}
		catch (IOException exception)
		{
			throw new IllegalArgumentException(
					"Could not read " + PROPERTY_PREFIX + "private.key.password.file '" + passwordFile + "'",
					exception);
		}
	}

	private static void validateClientIdentity(CryptoService crypto, List<X509Certificate> chain, PrivateKey key,
			Path chainFile, Path keyFile)
	{
		X509Certificate leaf = chain.get(0);
		String leafName = "client certificate '" + leaf.getSubjectX500Principal().getName() + "' in '" + chainFile
				+ "'";

		if (leaf.getBasicConstraints() != -1)
			throw new IllegalArgumentException(leafName + " is a CA certificate, not a client certificate");

		if (!crypto.isKeyPair(key, leaf.getPublicKey()))
			throw new IllegalArgumentException("Private key '" + keyFile + "' does not match the " + leafName);

		try
		{
			if (leaf.getExtendedKeyUsage() != null && !crypto.isClientCertificate(leaf))
				throw new IllegalArgumentException(leafName + " lacks TLS client authentication extended key usage");
		}
		catch (CertificateException exception)
		{
			throw new IllegalArgumentException("Could not read extended key usage of the " + leafName, exception);
		}

		// Validity is time dependent, not a configuration mistake: failing startup would make DSF terminate running
		// transfer instances on a routine restart. An expired identity is rejected by the register instead, which
		// fails each cycle with the watermark kept until the certificate is renewed and the BPE restarted.
		for (X509Certificate certificate : chain)
		{
			String certificateName = "'" + certificate.getSubjectX500Principal().getName() + "' in '" + chainFile + "'";
			try
			{
				certificate.checkValidity();
			}
			catch (CertificateException exception)
			{
				logger.error(
						"Register mTLS certificate {} is not currently valid (not before {}, not after {}); register cycles will fail until it is renewed and the BPE restarted",
						certificateName, certificate.getNotBefore(), certificate.getNotAfter());
				continue;
			}

			if (certificate.getNotAfter().toInstant().isBefore(Instant.now().plus(EXPIRY_WARNING)))
				logger.warn("Register mTLS certificate {} expires {}; renew it and restart the BPE before then",
						certificateName, certificate.getNotAfter());
		}
	}
}
