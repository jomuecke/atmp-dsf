package de.ukhd.process.atmp.client;

/** Sends ATMP collection Bundles to the external register. */
public interface RegisterClient
{
	/** Transport, authentication, or register-health failure that aborts the remaining cycle. */
	final class RegisterCycleException extends RuntimeException
	{
		public RegisterCycleException(String message, Throwable cause)
		{
			super(message, cause);
		}
	}

	/** Request or acknowledgement failure isolated to the subject being sent. */
	final class RegisterRejectedException extends RuntimeException
	{
		public RegisterRejectedException(String message, Throwable cause)
		{
			super(message, cause);
		}
	}

	/**
	 * Sends the supplied FHIR collection Bundle JSON to the register.
	 *
	 * @return the register acknowledgement, never {@code null}
	 * @throws RegisterCycleException
	 *             if the failure affects every subject in the current cycle
	 * @throws RegisterRejectedException
	 *             if the failure is isolated to the subject being sent
	 */
	RegisterAck send(String bundleJson);
}
