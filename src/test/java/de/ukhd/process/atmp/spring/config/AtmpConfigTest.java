package de.ukhd.process.atmp.spring.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.Field;

import org.junit.Test;

public class AtmpConfigTest
{
	@Test
	public void testRemovedMtlsPropertyFailsStartupForAnyValue() throws Exception
	{
		for (String value : new String[] { "true", "false", "", "unexpected" })
		{
			AtmpConfig config = new AtmpConfig();
			Field guard = AtmpConfig.class.getDeclaredField("removedMtlsEnabled");
			guard.setAccessible(true);
			guard.set(config, value);

			// The guard must run before accessing the crypto service or loading credentials.
			IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
					() -> config.registerClient(null));
			assertEquals("de.ukhd.atmp.api.mtls.enabled was removed; mTLS is mandatory, remove the property",
					exception.getMessage());
		}
	}
}
