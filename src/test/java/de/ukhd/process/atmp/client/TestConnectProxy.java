package de.ukhd.process.atmp.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Raw CONNECT proxy: challenges first, records the authenticated retry, and never opens a tunnel. */
final class TestConnectProxy implements AutoCloseable
{
	private final ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
	private final Thread worker;
	final AtomicInteger requests = new AtomicInteger();
	final AtomicReference<String> requestLine = new AtomicReference<>();
	final AtomicReference<String> authorization = new AtomicReference<>();
	final AtomicReference<IOException> failure = new AtomicReference<>();

	TestConnectProxy() throws IOException
	{
		worker = Thread.ofPlatform().daemon().start(() ->
		{
			try
			{
				while (!server.isClosed())
				{
					try (Socket socket = server.accept())
					{
						socket.setSoTimeout(5000);
						BufferedReader reader = new BufferedReader(
								new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
						requestLine.set(reader.readLine());
						requests.incrementAndGet();
						String header;
						while ((header = reader.readLine()) != null && !header.isEmpty())
							if (header.regionMatches(true, 0, "Proxy-Authorization:", 0, 20))
								authorization.set(header.substring(20).trim());
						// Closing after the challenge makes the connector retry on a fresh connection.
						socket.getOutputStream()
								.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
										+ "Proxy-Authenticate: Basic realm=\"atmp-test\"\r\n"
										+ "Content-Length: 0\r\nConnection: close\r\n\r\n")
										.getBytes(StandardCharsets.ISO_8859_1));
					}
				}
			}
			catch (IOException exception)
			{
				if (!server.isClosed())
					failure.set(exception);
			}
		});
	}

	int port()
	{
		return server.getLocalPort();
	}

	@Override
	public void close() throws IOException, InterruptedException
	{
		server.close();
		worker.join(5000);
	}
}
