package de.ukhd.process.atmp.client;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.SSLContext;

import de.hsheilbronn.mi.utils.crypto.cert.CertificateValidator;
import de.hsheilbronn.mi.utils.crypto.context.SSLContextFactory;
import de.hsheilbronn.mi.utils.crypto.io.KeyStoreReader;
import de.hsheilbronn.mi.utils.crypto.io.PemReader;
import de.hsheilbronn.mi.utils.crypto.keypair.KeyPairValidator;
import de.hsheilbronn.mi.utils.crypto.keystore.KeyStoreCreator;
import dev.dsf.bpe.v2.service.CryptoService;

/**
 * Stands in for the DSF BPE {@code CryptoServiceImpl}, delegating to the same crypto-utils library. Covers the methods
 * the register TLS setup uses; the rest are not needed by these tests.
 */
final class TestCryptoService implements CryptoService
{
	@Override
	public X509Certificate readCertificate(InputStream pem) throws IOException
	{
		return PemReader.readCertificate(pem);
	}

	@Override
	public List<X509Certificate> readCertificates(InputStream pem) throws IOException
	{
		return PemReader.readCertificates(pem);
	}

	@Override
	public PrivateKey readPrivateKey(InputStream pem, char[] password) throws IOException
	{
		return PemReader.readPrivateKey(pem, password);
	}

	@Override
	public boolean isKeyPair(PrivateKey privateKey, PublicKey publicKey)
	{
		return KeyPairValidator.matches(privateKey, publicKey);
	}

	@Override
	public boolean isCertificateExpired(X509Certificate certificate)
	{
		return CertificateValidator.isCertificateExpired(certificate);
	}

	@Override
	public boolean isClientCertificate(X509Certificate certificate)
	{
		return CertificateValidator.isClientCertificate(certificate);
	}

	@Override
	public boolean isServerCertificate(X509Certificate certificate)
	{
		return CertificateValidator.isServerCertificate(certificate);
	}

	@Override
	public void validateClientCertificate(KeyStore trustStore, Collection<? extends X509Certificate> certificateChain)
			throws CertificateException
	{
		CertificateValidator.validateClientCertificate(trustStore, certificateChain);
	}

	@Override
	public void validateServerCertificate(KeyStore trustStore, Collection<? extends X509Certificate> certificateChain)
			throws CertificateException
	{
		CertificateValidator.validateServerCertificate(trustStore, certificateChain);
	}

	@Override
	public KeyStore createKeyStoreForPrivateKeyAndCertificateChain(PrivateKey key, char[] password,
			Collection<? extends X509Certificate> chain)
	{
		return KeyStoreCreator.jksForPrivateKeyAndCertificateChain(key, password, chain);
	}

	@Override
	public KeyStore createKeyStoreForTrustedCertificates(Collection<? extends X509Certificate> certificates)
	{
		return KeyStoreCreator.jksForTrustedCertificates(certificates);
	}

	@Override
	public KeyStore readKeyStoreJks(InputStream stream, char[] password) throws IOException
	{
		return KeyStoreReader.readJks(stream, password);
	}

	@Override
	public KeyStore readKeyStorePkcs12(InputStream stream, char[] password) throws IOException
	{
		return KeyStoreReader.readPkcs12(stream, password);
	}

	@Override
	public SSLContext createSSLContext(KeyStore trustStore)
			throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyManagementException
	{
		return SSLContextFactory.createSSLContext(trustStore);
	}

	@Override
	public SSLContext createSSLContext(KeyStore trustStore, KeyStore keyStore, char[] keyStorePassword)
			throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyManagementException
	{
		return SSLContextFactory.createSSLContext(trustStore, keyStore, keyStorePassword);
	}

	@Override
	public Kem createRsaKem()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public Kem createEcDhKem()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public KeyPairGenerator createKeyPairGeneratorRsa4096AndInitialize()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public KeyPairGenerator createKeyPairGeneratorSecp256r1AndInitialize()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public KeyPairGenerator createKeyPairGeneratorSecp384r1AndInitialize()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public KeyPairGenerator createKeyPairGeneratorSecp521r1AndInitialize()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public KeyPairGenerator createKeyPairGeneratorX25519AndInitialize()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public KeyPairGenerator createKeyPairGeneratorX448AndInitialize()
	{
		throw new UnsupportedOperationException();
	}
}
