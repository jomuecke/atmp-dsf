package de.ukhd.process.atmp.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import org.glassfish.jersey.apache.connector.ApacheConnectorProvider;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.RequestEntityProcessing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.dsf.bpe.v2.config.ProxyConfig;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Jersey transport for the integrate-ATMP register API. Instances are fully initialized on construction and own their
 * underlying HTTP client.
 */
public final class RegisterClientJersey implements RegisterClient, AutoCloseable
{
	private static final Logger logger = LoggerFactory.getLogger(RegisterClientJersey.class);

	public static final String API_KEY_HEADER = "MEDIC-API-KEY";

	/** Upper bound on how much of an error body is carried into exception messages, logs and audit entries. */
	private static final int MAX_ERROR_BODY_LENGTH = 500;

	private final URI importEndpoint;
	private final Client httpClient;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final String apiKeyHeaderValue;

	/**
	 * @param importEndpointUrl
	 *            complete URL that accepts collection Bundles, not blank
	 * @param bhz
	 *            site identity used to build the Base64-encoded {@code MEDIC-API-KEY} JSON together with the raw key
	 *            file; blank preserves the legacy behavior where the file already contains the complete header value
	 * @param apiKeyFile
	 *            path to the API-key secret file, not {@code null}
	 * @param connectTimeout
	 *            how long to wait for the TCP/TLS connection, not {@code null}
	 * @param requestTimeout
	 *            how long to wait for the response, not {@code null}
	 * @param proxyConfig
	 *            DSF BPE proxy configuration, not {@code null}
	 * @param mtlsContext
	 *            client-certificate TLS context from {@link RegisterMtlsConfig}, not {@code null}
	 */
	public RegisterClientJersey(String importEndpointUrl, String bhz, String apiKeyFile, Duration connectTimeout,
			Duration requestTimeout, ProxyConfig proxyConfig, SSLContext mtlsContext) throws IOException
	{
		this.importEndpoint = configuredEndpoint(importEndpointUrl);
		this.apiKeyHeaderValue = apiKeyHeaderValue(bhz, apiKeyFile);
		this.httpClient = createHttpClient(importEndpoint, connectTimeout, requestTimeout,
				Objects.requireNonNull(proxyConfig, "proxyConfig"), Objects.requireNonNull(mtlsContext, "mtlsContext"));
	}

	private String apiKeyHeaderValue(String bhz, String apiKeyFile) throws IOException
	{
		Path keyFile = Path.of(Objects.requireNonNull(apiKeyFile, "apiKeyFile"));
		String keyFileValue = Files.readString(keyFile).trim();

		if (keyFileValue.isEmpty())
			throw new IllegalArgumentException("Register API key file '" + keyFile + "' is empty");

		String trimmedBhz = bhz == null ? null : bhz.trim();
		if (trimmedBhz == null || trimmedBhz.isBlank())
			return keyFileValue;

		byte[] authJson = objectMapper.createObjectNode().put("bhz", trimmedBhz).put("apiKey", keyFileValue).toString()
				.getBytes(StandardCharsets.UTF_8);
		return Base64.getEncoder().encodeToString(authJson);
	}

	private Client createHttpClient(URI endpoint, Duration connectTimeout, Duration requestTimeout,
			ProxyConfig proxyConfig, SSLContext mtlsContext)
	{
		ClientConfig clientConfig = new ClientConfig().connectorProvider(new ApacheConnectorProvider())
				.property(ClientProperties.CONNECT_TIMEOUT, timeoutMilliseconds(connectTimeout, "connectTimeout"))
				.property(ClientProperties.READ_TIMEOUT, timeoutMilliseconds(requestTimeout, "requestTimeout"))
				// A proxy may issue a 407 challenge. Buffering keeps this POST body repeatable for the authenticated
				// retry.
				.property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.BUFFERED)
				// A redirect must not carry the API key or client identity to another destination.
				.property(ClientProperties.FOLLOW_REDIRECTS, false);

		boolean proxyEnabled = proxyConfig.isEnabled(endpoint.toString());
		if (proxyEnabled)
		{
			char[] password = proxyConfig.getPassword();
			clientConfig.property(ClientProperties.PROXY_URI, proxyConfig.getUrl());
			clientConfig.property(ClientProperties.PROXY_USERNAME, proxyConfig.getUsername());
			clientConfig.property(ClientProperties.PROXY_PASSWORD, password == null ? null : String.valueOf(password));
		}

		logger.info("Register API client configured for endpoint '{}' with DSF outbound proxy {} and mTLS enabled",
				endpoint, proxyEnabled ? "enabled" : "disabled");
		return ClientBuilder.newBuilder().withConfig(clientConfig).sslContext(mtlsContext).build();
	}

	private static int timeoutMilliseconds(Duration timeout, String name)
	{
		long milliseconds = Objects.requireNonNull(timeout, name).toMillis();
		if (milliseconds <= 0 || milliseconds > Integer.MAX_VALUE)
			throw new IllegalArgumentException(name + " must be between PT0.001S and PT596H");

		return (int) milliseconds;
	}

	private static URI configuredEndpoint(String importEndpointUrl)
	{
		Objects.requireNonNull(importEndpointUrl, "importEndpointUrl");
		String trimmed = importEndpointUrl.trim();
		if (trimmed.isEmpty())
			throw new IllegalArgumentException("importEndpointUrl is empty");

		return URI.create(trimmed);
	}

	@Override
	public RegisterAck send(String bundleJson)
	{
		int status;
		String body;
		try
		{
			try (Response response = httpClient.target(importEndpoint).request(MediaType.APPLICATION_JSON_TYPE)
					.header(API_KEY_HEADER, apiKeyHeaderValue)
					.post(Entity.entity(bundleJson, MediaType.APPLICATION_JSON_TYPE)))
			{
				status = response.getStatus();
				body = response.hasEntity() ? response.readEntity(String.class) : "";
			}
		}
		catch (ProcessingException exception)
		{
			throw new RegisterCycleException("Could not reach register API at '" + importEndpoint + "': "
					+ exception.getMessage() + connectionFailureHint(exception), exception);
		}

		if (status == 401)
			throw new RegisterCycleException(
					"Register API at '" + importEndpoint + "' rejected the " + API_KEY_HEADER + " header (status 401): "
							+ truncated(body)
							+ " — E060 header missing, E061 unknown bhz, E062 wrong apiKey, E063 malformed header",
					null);

		if (status == 403)
			throw new RegisterCycleException("Register API at '" + importEndpoint
					+ "' denied access (status 403), e.g. a rejected client certificate at the register ingress or a missing import permission: "
					+ truncated(body), null);

		if (status >= 300 && status <= 399)
			throw new RegisterCycleException("Register API at '" + importEndpoint + "' answered with redirect status "
					+ status + ", redirects are not followed; configure the final import URL", null);

		if (status == 407)
			throw new RegisterCycleException(
					"DSF outbound proxy rejected its configured credentials while connecting to register API at '"
							+ importEndpoint + "' (status 407): " + truncated(body),
					null);

		if (status >= 500)
			throw new RegisterCycleException(
					"Register API at '" + importEndpoint + "' returned status " + status + ": " + truncated(body),
					null);

		if (status < 200 || status > 299)
			throw new RegisterRejectedException("Register API at '" + importEndpoint
					+ "' rejected the bundle with status " + status + ": " + truncated(body), null);

		RegisterAck ack = parseAck(status, body);

		if (ack.isFullyAccepted())
			logger.info("Sent bundle to register API at '{}', status {}, fully accepted", importEndpoint, status);
		else
			logger.warn("Register API at '{}' answered status {} but did not accept everything: {}", importEndpoint,
					status, ack.describeProblems());

		return ack;
	}

	private String connectionFailureHint(ProcessingException exception)
	{
		for (Throwable cause = exception; cause != null; cause = cause.getCause())
		{
			if (cause instanceof SSLException)
				return " — TLS connection failed: check the register server certificate trust and hostname"
						+ ", and that the register accepts this site's client certificate";

			// A register rejecting the client certificate after a TLS 1.3 handshake shows up as a broken connection
			if (cause instanceof SocketException && !(cause instanceof ConnectException))
				return " — the register closed the connection; with mTLS this may mean it rejected the client certificate";
		}

		return "";
	}

	private RegisterAck parseAck(int httpStatus, String body)
	{
		if (body == null || body.isBlank())
		{
			if (httpStatus == 207)
				return incompleteMultiStatusAck();

			logger.warn("Register API at '{}' returned an empty acknowledgement body, assuming the bundle was accepted",
					importEndpoint);
			return RegisterAck.accepted();
		}

		try
		{
			JsonNode json = objectMapper.readTree(body);

			if (!json.isObject())
				throw new RegisterRejectedException("Register API at '" + importEndpoint
						+ "' returned a non-object acknowledgement: " + truncated(body), null);

			RegisterAck ack = RegisterAck.from(json);
			return httpStatus == 207 && ack.isFullyAccepted() ? incompleteMultiStatusAck() : ack;
		}
		catch (IOException exception)
		{
			// Not knowing whether the data arrived must not read as success: treat it as a failure of this subject
			throw new RegisterRejectedException("Could not read the acknowledgement of register API at '"
					+ importEndpoint + "': " + exception.getMessage() + ", body: " + truncated(body), exception);
		}
	}

	private RegisterAck incompleteMultiStatusAck()
	{
		return new RegisterAck(false, List.of(),
				List.of("HTTP 207 Multi-Status did not contain a usable skipped or rejected entry"));
	}

	private String truncated(String body)
	{
		if (body == null)
			return "";

		return body.length() <= MAX_ERROR_BODY_LENGTH ? body
				: body.substring(0, MAX_ERROR_BODY_LENGTH) + "… (truncated)";
	}

	@Override
	public void close()
	{
		httpClient.close();
	}
}
