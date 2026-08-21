package de.ukhd.process.atmp.client;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.glassfish.jersey.apache.connector.ApacheConnectorProvider;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.RequestEntityProcessing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

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
 * REST client for the external ATMP register (integrate-ATMP API). POSTs FHIR collection Bundles (JSON) to the exact
 * configured endpoint URL, authenticated with the site's {@code MEDIC-API-KEY} header. The secret is read from a
 * docker-secret file and never logged.
 *
 * <p>
 * Failures are classified for the caller (see {@link RegisterException#affectsWholeCycle()}), because they need
 * different handling: a misconfigured API key or an unreachable/unhealthy register fails identically for every subject
 * and should stop the cycle instead of producing one audit entry per subject, whereas a rejected bundle is specific to
 * the subject being sent and must not stop the others.
 */
public class RegisterClient implements InitializingBean, DisposableBean
{
	/** Base class of the failures the register can produce, classified by blast radius. */
	public abstract static class RegisterException extends RuntimeException
	{
		protected RegisterException(String message, Throwable cause)
		{
			super(message, cause);
		}

		/**
		 * {@code true} if the failure affects every subject of the cycle (transport, auth, register health) rather than
		 * just the bundle that was sent.
		 */
		public abstract boolean affectsWholeCycle();
	}

	/**
	 * Thrown when the register cannot be reached at all (connection failure, timeout, IO error, as opposed to a
	 * per-request error status): the whole cycle is affected, not just the current subject.
	 */
	public static class RegisterUnreachableException extends RegisterException
	{
		public RegisterUnreachableException(String message, Throwable cause)
		{
			super(message, cause);
		}

		@Override
		public boolean affectsWholeCycle()
		{
			return true;
		}
	}

	/**
	 * Thrown on HTTP 401: the {@code MEDIC-API-KEY} is missing, malformed, or its {@code bhz}/{@code apiKey} is not
	 * accepted ({@code E060}&ndash;{@code E063}). A configuration fault — every subject would fail the same way, so it
	 * stops the cycle rather than retrying per subject.
	 */
	public static class RegisterAuthException extends RegisterException
	{
		public RegisterAuthException(String message)
		{
			super(message, null);
		}

		@Override
		public boolean affectsWholeCycle()
		{
			return true;
		}
	}

	/** Thrown on HTTP 407: the DSF outbound proxy rejected its configured credentials. */
	public static class RegisterProxyAuthException extends RegisterException
	{
		public RegisterProxyAuthException(String message)
		{
			super(message, null);
		}

		@Override
		public boolean affectsWholeCycle()
		{
			return true;
		}
	}

	/** Thrown on HTTP 5xx: the register is up but unhealthy, which again affects every subject of the cycle. */
	public static class RegisterUnavailableException extends RegisterException
	{
		public RegisterUnavailableException(String message)
		{
			super(message, null);
		}

		@Override
		public boolean affectsWholeCycle()
		{
			return true;
		}
	}

	/**
	 * Thrown when the register rejected this specific request — an {@code E064} bundle-schema failure (HTTP 400), any
	 * other 4xx, or an acknowledgement that could not be read. Isolated to the current subject.
	 */
	public static class RegisterRejectedException extends RegisterException
	{
		public RegisterRejectedException(String message, Throwable cause)
		{
			super(message, cause);
		}

		@Override
		public boolean affectsWholeCycle()
		{
			return false;
		}
	}

	private static final Logger logger = LoggerFactory.getLogger(RegisterClient.class);

	public static final String API_KEY_HEADER = "MEDIC-API-KEY";

	public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
	public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

	/** Upper bound on how much of an error body is carried into exception messages, logs and audit entries. */
	private static final int MAX_ERROR_BODY_LENGTH = 500;

	private final URI importEndpoint;
	private final String bhz;
	private final Path apiKeyFile;
	private final Duration connectTimeout;
	private final Duration requestTimeout;
	private final ProxyConfig proxyConfig;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private Client httpClient;
	private String apiKeyHeaderValue;

	public RegisterClient(String importEndpointUrl, String apiKeyFile)
	{
		this(importEndpointUrl, null, apiKeyFile, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT);
	}

	/**
	 * @param connectTimeout
	 *            how long to wait for the TCP/TLS connection, not <code>null</code>
	 * @param requestTimeout
	 *            how long to wait for the complete response; without it a hung register would block this cycle's BPE
	 *            job thread indefinitely, not <code>null</code>
	 */
	public RegisterClient(String importEndpointUrl, String apiKeyFile, Duration connectTimeout, Duration requestTimeout)
	{
		this(importEndpointUrl, null, apiKeyFile, connectTimeout, requestTimeout);
	}

	/**
	 * @param importEndpointUrl
	 *            complete URL that accepts collection Bundles, not blank
	 * @param bhz
	 *            site identity used to build the Base64-encoded {@code MEDIC-API-KEY} JSON together with the raw key
	 *            file; blank preserves the legacy behavior where the file already contains the complete header value
	 * @param connectTimeout
	 *            how long to wait for the TCP/TLS connection, not <code>null</code>
	 * @param requestTimeout
	 *            how long to wait for the complete response; without it a hung register would block this cycle's BPE
	 *            job thread indefinitely, not <code>null</code>
	 */
	public RegisterClient(String importEndpointUrl, String bhz, String apiKeyFile, Duration connectTimeout,
			Duration requestTimeout)
	{
		this(importEndpointUrl, bhz, apiKeyFile, connectTimeout, requestTimeout, null);
	}

	public RegisterClient(String importEndpointUrl, String apiKeyFile, Duration connectTimeout, Duration requestTimeout,
			ProxyConfig proxyConfig)
	{
		this(importEndpointUrl, null, apiKeyFile, connectTimeout, requestTimeout, proxyConfig);
	}

	/**
	 * @param proxyConfig
	 *            DSF BPE proxy configuration, or <code>null</code> to always connect directly
	 */
	public RegisterClient(String importEndpointUrl, String bhz, String apiKeyFile, Duration connectTimeout,
			Duration requestTimeout, ProxyConfig proxyConfig)
	{
		this.importEndpoint = configuredEndpoint(importEndpointUrl);
		this.bhz = bhz == null ? null : bhz.trim();
		this.apiKeyFile = apiKeyFile == null ? null : Path.of(apiKeyFile);
		this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
		this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
		this.proxyConfig = proxyConfig;
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(apiKeyFile, "apiKeyFile");

		String keyFileValue = Files.readString(apiKeyFile).trim();

		if (keyFileValue.isEmpty())
			throw new IllegalArgumentException("Register API key file '" + apiKeyFile + "' is empty");

		if (bhz == null || bhz.isBlank())
			apiKeyHeaderValue = keyFileValue;
		else
		{
			byte[] authJson = objectMapper.createObjectNode().put("bhz", bhz).put("apiKey", keyFileValue).toString()
					.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			apiKeyHeaderValue = Base64.getEncoder().encodeToString(authJson);
		}

		httpClient = createHttpClient();
	}

	private Client createHttpClient()
	{
		ClientConfig clientConfig = new ClientConfig().connectorProvider(new ApacheConnectorProvider())
				.property(ClientProperties.CONNECT_TIMEOUT, timeoutMilliseconds(connectTimeout, "connectTimeout"))
				.property(ClientProperties.READ_TIMEOUT, timeoutMilliseconds(requestTimeout, "requestTimeout"))
				// A proxy may issue a 407 challenge. Buffering keeps this POST body repeatable for the authenticated
				// retry.
				.property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.BUFFERED);

		boolean proxyEnabled = proxyConfig != null && proxyConfig.isEnabled(importEndpoint.toString());
		if (proxyEnabled)
		{
			clientConfig.property(ClientProperties.PROXY_URI, proxyConfig.getUrl());
			clientConfig.property(ClientProperties.PROXY_USERNAME, proxyConfig.getUsername());
			clientConfig.property(ClientProperties.PROXY_PASSWORD,
					proxyConfig.getPassword() == null ? null : String.valueOf(proxyConfig.getPassword()));
		}

		logger.info("Register API client configured for endpoint '{}' with DSF outbound proxy {}", importEndpoint,
				proxyEnabled ? "enabled" : "disabled");
		return ClientBuilder.newBuilder().withConfig(clientConfig).build();
	}

	private static int timeoutMilliseconds(Duration timeout, String name)
	{
		long milliseconds = timeout.toMillis();
		if (milliseconds <= 0 || milliseconds > Integer.MAX_VALUE)
			throw new IllegalArgumentException(name + " must be between PT0.001S and PT596H");

		return (int) milliseconds;
	}

	@Override
	public void destroy() throws Exception
	{
		if (httpClient != null)
			httpClient.close();
	}

	private static URI configuredEndpoint(String importEndpointUrl)
	{
		Objects.requireNonNull(importEndpointUrl, "importEndpointUrl");
		String trimmed = importEndpointUrl.trim();
		if (trimmed.isEmpty())
			throw new IllegalArgumentException("importEndpointUrl is empty");

		return URI.create(trimmed);
	}

	/**
	 * POSTs {@code bundleJson} to the register's import endpoint.
	 *
	 * <p>
	 * A returned ack does not by itself mean the data arrived — see {@link RegisterAck#isFullyAccepted()}. The
	 * {@code Content-Type} is {@code application/json} rather than {@code application/fhir+json}: the register is a
	 * plain REST API validating a JSON body, not a FHIR server.
	 *
	 * @return the register's acknowledgement, never <code>null</code>
	 * @throws RegisterException
	 *             on transport failure or a non-2xx status, classified by {@link RegisterException#affectsWholeCycle()}
	 */
	public RegisterAck send(String bundleJson)
	{
		Objects.requireNonNull(httpClient, "RegisterClient not initialized");

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
			throw new RegisterUnreachableException(
					"Could not reach register API at '" + importEndpoint + "': " + exception.getMessage(), exception);
		}

		if (status == 401)
			throw new RegisterAuthException("Register API at '" + importEndpoint + "' rejected the " + API_KEY_HEADER
					+ " header (status 401): " + truncated(body)
					+ " — E060 header missing, E061 unknown bhz, E062 wrong apiKey, E063 malformed header");

		if (status == 407)
			throw new RegisterProxyAuthException(
					"DSF outbound proxy rejected its configured credentials while connecting to register API at '"
							+ importEndpoint + "' (status 407): " + truncated(body));

		if (status >= 500)
			throw new RegisterUnavailableException(
					"Register API at '" + importEndpoint + "' returned status " + status + ": " + truncated(body));

		if (status < 200 || status > 299)
			throw new RegisterRejectedException("Register API at '" + importEndpoint
					+ "' rejected the bundle with status " + status + ": " + truncated(body), null);

		RegisterAck ack = parseAck(importEndpoint, status, body);

		if (ack.isFullyAccepted())
			logger.info("Sent bundle to register API at '{}', status {}, fully accepted", importEndpoint, status);
		else
			logger.warn("Register API at '{}' answered status {} but did not accept everything: {}", importEndpoint,
					status, ack.describeProblems());

		return ack;
	}

	private RegisterAck parseAck(URI uri, int httpStatus, String body)
	{
		if (body == null || body.isBlank())
		{
			if (httpStatus == 207)
				return incompleteMultiStatusAck();

			logger.warn("Register API at '{}' returned an empty acknowledgement body, assuming the bundle was accepted",
					uri);
			return RegisterAck.accepted();
		}

		try
		{
			JsonNode json = objectMapper.readTree(body);

			if (!json.isObject())
				throw new RegisterRejectedException(
						"Register API at '" + uri + "' returned a non-object acknowledgement: " + truncated(body),
						null);

			RegisterAck ack = RegisterAck.from(json);
			return httpStatus == 207 && ack.isFullyAccepted() ? incompleteMultiStatusAck() : ack;
		}
		catch (IOException exception)
		{
			// Not knowing whether the data arrived must not read as success: treat it as a failure of this subject
			throw new RegisterRejectedException("Could not read the acknowledgement of register API at '" + uri + "': "
					+ exception.getMessage() + ", body: " + truncated(body), exception);
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
}
