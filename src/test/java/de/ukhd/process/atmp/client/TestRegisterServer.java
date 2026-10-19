package de.ukhd.process.atmp.client;

import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import de.hsheilbronn.mi.utils.crypto.context.SSLContextFactory;
import de.hsheilbronn.mi.utils.crypto.keystore.KeyStoreCreator;
import de.ukhd.process.atmp.client.TestPki.Identity;

/** Local HTTPS register requiring a client certificate, shared by response and transport tests. */
final class TestRegisterServer
{
	private TestRegisterServer()
	{
	}

	static HttpsServer create(Identity server, X509Certificate trustedClientCa) throws Exception
	{
		char[] password = UUID.randomUUID().toString().toCharArray();
		SSLContext context = SSLContextFactory.createSSLContext(
				KeyStoreCreator.jksForTrustedCertificates(trustedClientCa), KeyStoreCreator
						.jksForPrivateKeyAndCertificateChain(server.privateKey(), password, server.certificate()),
				password);
		HttpsServer register = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		register.setHttpsConfigurator(new HttpsConfigurator(context)
		{
			@Override
			public void configure(HttpsParameters parameters)
			{
				SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
				sslParameters.setNeedClientAuth(true);
				parameters.setSSLParameters(sslParameters);
			}
		});
		return register;
	}
}
