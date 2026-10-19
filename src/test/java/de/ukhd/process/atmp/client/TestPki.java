package de.ukhd.process.atmp.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.temporal.TemporalAmount;
import java.util.List;

import de.hsheilbronn.mi.utils.crypto.ca.CertificateAuthority;
import de.hsheilbronn.mi.utils.crypto.ca.CertificationRequest;
import de.hsheilbronn.mi.utils.crypto.ca.CertificationRequest.CertificationRequestAndPrivateKey;
import de.hsheilbronn.mi.utils.crypto.io.PemWriter;

/** Throwaway test CA issuing the register server and ATMP client identities used by the transport tests. */
final class TestPki
{
	record Identity(X509Certificate certificate, PrivateKey privateKey, X509Certificate issuer)
	{
	}

	private final CertificateAuthority ca;

	TestPki(String name)
	{
		ca = CertificateAuthority.builderSha384EcdsaSecp384r1("DE", null, null, null, null, name + " Test CA").build();
	}

	X509Certificate caCertificate()
	{
		return ca.getCertificate();
	}

	PrivateKey caPrivateKey()
	{
		return ca.getKeyPair().getPrivate();
	}

	Identity server(String... dnsNames)
	{
		CertificationRequestAndPrivateKey request = request("register").setDnsNames(dnsNames).build();
		return new Identity(ca.signServerCertificate(request), request.getPrivateKey(), ca.getCertificate());
	}

	Identity server(TemporalAmount validity, String... dnsNames)
	{
		CertificationRequestAndPrivateKey request = request("register").setDnsNames(dnsNames).build();
		return new Identity(ca.signServerCertificate(request, validity), request.getPrivateKey(), ca.getCertificate());
	}

	Identity client(String name)
	{
		CertificationRequestAndPrivateKey request = request(name).build();
		return new Identity(ca.signClientCertificate(request), request.getPrivateKey(), ca.getCertificate());
	}

	Identity client(String name, TemporalAmount validity)
	{
		CertificationRequestAndPrivateKey request = request(name).build();
		return new Identity(ca.signClientCertificate(request, validity), request.getPrivateKey(), ca.getCertificate());
	}

	private CertificationRequest.CertificationRequestBuilder request(String commonName)
	{
		return CertificationRequest.builder(ca, "DE", null, null, null, null, commonName).generateKeyPair();
	}

	static Path writeChain(Path directory, Identity identity) throws IOException
	{
		Path file = directory.resolve("client-chain.pem");
		PemWriter.writeCertificates(List.of(identity.certificate(), identity.issuer()), file);
		return file;
	}

	static Path writeKey(Path directory, PrivateKey key) throws IOException
	{
		Path file = directory.resolve("client-key.pem");
		PemWriter.writePrivateKey(key).asPkcs8().notEncrypted().toFile(file);
		return file;
	}

	static Path writeEncryptedKey(Path directory, PrivateKey key, char[] password) throws IOException
	{
		Path file = directory.resolve("client-key-encrypted.pem");
		PemWriter.writePrivateKey(key).asPkcs8().encryptedAes256(password).toFile(file);
		return file;
	}

	static Path writeCertificates(Path file, X509Certificate... certificates) throws IOException
	{
		PemWriter.writeCertificates(certificates, file);
		return file;
	}
}
