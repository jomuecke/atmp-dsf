package de.ukhd.process.atmp.client;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The register's import acknowledgement returned by {@code POST /medic-import}.
 *
 * <p>
 * Supports both acknowledgement contracts used by the ATMP register implementations: the original
 * {@code success/failures/parseIssues} shape and the DIZ {@code entries[]} shape. A caller that only checks the HTTP
 * status can silently lose data, especially for a DIZ HTTP 207 response, so {@link #isFullyAccepted()} is what the
 * process acts on.
 */
public record RegisterAck(boolean success, List<Failure> failures, List<String> parseIssues)
{
	/**
	 * @param canBeSkippedAsError
	 *            {@code true} for a failure the register considers tolerable — in practice an unknown PID, i.e. a
	 *            pseudonym not (yet) provisioned in the register
	 */
	public record Failure(String reason, boolean canBeSkippedAsError)
	{
		@Override
		public String toString()
		{
			return reason + (canBeSkippedAsError ? " (skippable)" : "");
		}
	}

	/** Ack for a successful non-207 response with no body to inspect: accepted, with nothing further known. */
	public static RegisterAck accepted()
	{
		return new RegisterAck(true, List.of(), List.of());
	}

	/**
	 * Reads the ack leniently from the parsed response body: unknown properties are ignored and a missing
	 * {@code success} is taken as {@code true}, since the register only returns 2xx once the envelope was accepted —
	 * absence of the flag is not evidence of failure, whereas an explicit {@code false} is.
	 */
	public static RegisterAck from(JsonNode body)
	{
		if (body.has("entries"))
			return fromDizEntries(body.get("entries"));

		boolean success = !body.hasNonNull("success") || body.get("success").asBoolean(true);

		List<Failure> failures = new ArrayList<>();
		if (body.has("failures") && body.get("failures").isArray())
			body.get("failures").forEach(f -> failures
					.add(new Failure(f.path("reason").asText(""), f.path("canBeSkippedAsError").asBoolean(false))));

		List<String> parseIssues = new ArrayList<>();
		if (body.has("parseIssues") && body.get("parseIssues").isArray())
			body.get("parseIssues").forEach(i -> parseIssues.add(i.toString()));

		return new RegisterAck(success, List.copyOf(failures), List.copyOf(parseIssues));
	}

	private static RegisterAck fromDizEntries(JsonNode entries)
	{
		if (!entries.isArray())
			return new RegisterAck(false, List.of(),
					List.of("Register acknowledgement field 'entries' is not an array"));

		List<Failure> failures = new ArrayList<>();
		List<String> parseIssues = new ArrayList<>();

		entries.forEach(entry ->
		{
			String status = entry.path("status").asText("");
			switch (status)
			{
				case "stored" -> {
				}
				case "skipped" -> {
					String code = entry.path("code").asText("");
					String reason = entry.path("reason").asText("");
					String description = code + (reason.isBlank() ? "" : ": " + reason);
					failures.add(new Failure(description, "DIZ-E101".equals(code)));
				}
				case "rejected" -> parseIssues.add(entry.toString());
				default -> parseIssues.add("Unknown register entry status: " + entry);
			}
		});

		return new RegisterAck(true, List.copyOf(failures), List.copyOf(parseIssues));
	}

	/** {@code true} only if the register reported no problem at all with any entry. */
	public boolean isFullyAccepted()
	{
		return success && failures.isEmpty() && parseIssues.isEmpty();
	}

	/**
	 * Failures the register itself marked as skippable (unknown PID). The process still treats the subject as not
	 * transferred so it is retried, but distinguishes them from hard failures in the audit message.
	 */
	public List<Failure> skippableFailures()
	{
		return failures.stream().filter(Failure::canBeSkippedAsError).toList();
	}

	public List<Failure> hardFailures()
	{
		return failures.stream().filter(f -> !f.canBeSkippedAsError()).toList();
	}

	/** Human-readable summary for logs and audit entries; empty when {@link #isFullyAccepted()}. */
	public String describeProblems()
	{
		if (isFullyAccepted())
			return "";

		StringBuilder description = new StringBuilder();

		if (!success)
			description.append("success=false");
		if (!failures.isEmpty())
			description.append(description.isEmpty() ? "" : ", ").append(failures.size()).append(" failure(s): ")
					.append(failures);
		if (!parseIssues.isEmpty())
			description.append(description.isEmpty() ? "" : ", ").append(parseIssues.size())
					.append(" entry/entries rejected by the register's schema: ").append(parseIssues);

		return description.toString();
	}
}
