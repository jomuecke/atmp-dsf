package de.ukhd.process.atmp.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * REST client for the external ATMP register (MEDIC / integrate-ATMP API). POSTs FHIR collection Bundles (JSON) to
 * {@code {apiUrl}/api/medic-import}, authenticated with the site's API key sent as {@code MEDIC-API-KEY} header. The
 * key is read from a docker-secret file and never logged.
 */
public class RegisterClient implements InitializingBean
{
	/**
	 * Thrown when the register cannot be reached at all (connection/IO failure, as opposed to a per-request error
	 * status): the whole cycle is affected, not just the current subject.
	 */
	public static class RegisterUnreachableException extends RuntimeException
	{
		public RegisterUnreachableException(String message, Throwable cause)
		{
			super(message, cause);
		}
	}

	private static final Logger logger = LoggerFactory.getLogger(RegisterClient.class);

	public static final String IMPORT_PATH = "/api/medic-import";
	public static final String API_KEY_HEADER = "MEDIC-API-KEY";

	private final String apiUrl;
	private final Path apiKeyFile;

	private final HttpClient httpClient = HttpClient.newHttpClient();
	private String apiKey;

	public RegisterClient(String apiUrl, String apiKeyFile)
	{
		this.apiUrl = apiUrl;
		this.apiKeyFile = apiKeyFile == null ? null : Path.of(apiKeyFile);
	}

	@Override
	public void afterPropertiesSet() throws Exception
	{
		Objects.requireNonNull(apiUrl, "apiUrl");
		Objects.requireNonNull(apiKeyFile, "apiKeyFile");

		apiKey = Files.readString(apiKeyFile).trim();

		if (apiKey.isEmpty())
			throw new IllegalArgumentException("Register API key file '" + apiKeyFile + "' is empty");
	}

	public void send(String bundleJson)
	{
		URI uri = URI.create(apiUrl.replaceAll("/+$", "") + IMPORT_PATH);

		HttpRequest request = HttpRequest.newBuilder(uri).header("Content-Type", "application/fhir+json")
				.header(API_KEY_HEADER, apiKey).POST(HttpRequest.BodyPublishers.ofString(bundleJson)).build();

		HttpResponse<String> response;
		try
		{
			response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException | InterruptedException exception)
		{
			if (exception instanceof InterruptedException)
				Thread.currentThread().interrupt();

			throw new RegisterUnreachableException(
					"Could not reach register API at '" + uri + "': " + exception.getMessage(), exception);
		}

		if (response.statusCode() < 200 || response.statusCode() > 299)
			throw new RuntimeException(
					"Register API at '" + uri + "' returned status " + response.statusCode() + ": " + response.body());

		logger.info("Sent bundle to register API at '{}', status {}", uri, response.statusCode());
	}
}
