package de.ukhd.process.atmp.bpe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import de.ukhd.process.atmp.AtmpProcessPluginDefinition;
import de.ukhd.process.atmp.ConstantsAtmp;
import dev.dsf.bpe.v2.ProcessPluginDefinition;

public class AtmpProcessPluginDefinitionTest
{
	@Test
	public void testResourceLoading()
	{
		ProcessPluginDefinition definition = new AtmpProcessPluginDefinition();
		Map<String, List<String>> resourcesByProcessId = definition.getFhirResourcesByProcessId();

		var dataTransfer = resourcesByProcessId.get(ConstantsAtmp.PROCESS_NAME_FULL_ATMP_DATA_TRANSFER);
		assertNotNull(dataTransfer);
		assertEquals(5, dataTransfer.stream().filter(this::exists).count());
	}

	@Test
	public void testProcessModelLoading()
	{
		ProcessPluginDefinition definition = new AtmpProcessPluginDefinition();
		assertEquals(1, definition.getProcessModels().stream().filter(this::exists).count());
	}

	private boolean exists(String file)
	{
		return getClass().getClassLoader().getResourceAsStream(file) != null;
	}
}
