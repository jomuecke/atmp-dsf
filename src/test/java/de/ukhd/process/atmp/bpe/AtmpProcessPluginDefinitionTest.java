package de.ukhd.process.atmp.bpe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.Test;
import org.w3c.dom.Document;

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
		assertEquals(7, dataTransfer.stream().filter(this::exists).count());
	}

	@Test
	public void testProcessModelLoading()
	{
		ProcessPluginDefinition definition = new AtmpProcessPluginDefinition();
		assertEquals(1, definition.getProcessModels().stream().filter(this::exists).count());
	}

	@Test
	public void testTimerLoopAndStopMessageInProcessModel() throws Exception
	{
		Document document = readProcessModel();
		var xpath = XPathFactory.newInstance().newXPath();
		xpath.setNamespaceContext(new NamespaceContext()
		{
			@Override
			public String getNamespaceURI(String prefix)
			{
				if ("bpmn".equals(prefix))
					return "http://www.omg.org/spec/BPMN/20100524/MODEL";
				if ("camunda".equals(prefix))
					return "http://camunda.org/schema/1.0/bpmn";

				return XMLConstants.NULL_NS_URI;
			}

			@Override
			public String getPrefix(String namespaceURI)
			{
				return null;
			}

			@Override
			public java.util.Iterator<String> getPrefixes(String namespaceURI)
			{
				return java.util.Collections.emptyIterator();
			}
		});

		assertEquals(1.0, (Double) xpath.evaluate(
				"count(//bpmn:serviceTask[@id='Activity_rejectDuplicateStart' and @camunda:class='de.ukhd.process.atmp.service.RejectDuplicateStart'])",
				document, XPathConstants.NUMBER), 0.0);
		assertEquals("Activity_rejectDuplicateStart", xpath.evaluate(
				"string(//bpmn:sequenceFlow[@id='Flow_start_to_rejectDuplicateStart']/@targetRef)", document));
		assertEquals("Gateway_duplicateStart", xpath.evaluate(
				"string(//bpmn:sequenceFlow[@id='Flow_rejectDuplicateStart_to_duplicateStartGateway']/@targetRef)",
				document));
		assertEquals("Flow_duplicateStartGateway_to_transferLoop",
				xpath.evaluate("string(//bpmn:exclusiveGateway[@id='Gateway_duplicateStart']/@default)", document));
		assertEquals("${atmpDuplicateStart}", xpath.evaluate(
				"string(//bpmn:sequenceFlow[@id='Flow_duplicateStartGateway_to_rejected']/bpmn:conditionExpression)",
				document));
		assertEquals("Activity_transferLoop", xpath.evaluate(
				"string(//bpmn:sequenceFlow[@id='Flow_duplicateStartGateway_to_transferLoop']/@targetRef)", document));
		assertEquals(1.0, (Double) xpath.evaluate(
				"count(//bpmn:serviceTask[@id='Activity_setTimer' and @camunda:class='de.ukhd.process.atmp.service.SetTimer'])",
				document, XPathConstants.NUMBER), 0.0);
		assertEquals("${atmpTimerInterval}", xpath.evaluate(
				"string(//bpmn:intermediateCatchEvent[@id='Event_timerInterval']/bpmn:timerEventDefinition/bpmn:timeDuration)",
				document));
		assertEquals("Event_timerInterval",
				xpath.evaluate("string(//bpmn:sequenceFlow[@id='Flow_subprocess_to_timer']/@targetRef)", document));
		assertEquals("Activity_queryResearchSubjects",
				xpath.evaluate("string(//bpmn:sequenceFlow[@id='Flow_timer_to_query']/@targetRef)", document));
		// stop = message start event (new instance, no business-key correlation needed) broadcasting a signal that the
		// interrupting boundary event of the running loop instance catches
		assertEquals(1.0, (Double) xpath.evaluate(
				"count(//bpmn:startEvent[@id='StartEvent_atmpDataTransferStop']/bpmn:messageEventDefinition[@messageRef='Message_atmpDataTransferStop'])",
				document, XPathConstants.NUMBER), 0.0);
		assertEquals(1.0, (Double) xpath.evaluate(
				"count(//bpmn:endEvent[@id='EndEvent_stopSignalSent']/bpmn:signalEventDefinition[@signalRef='Signal_atmpDataTransferStop'])",
				document, XPathConstants.NUMBER), 0.0);
		assertEquals("stop_ukhdde_atmpDataTransfer_#{version}",
				xpath.evaluate("string(//bpmn:signal[@id='Signal_atmpDataTransferStop']/@name)", document));
		// The stop catch must leave the transfer-loop scope and reach a top-level end event. An interrupting event
		// subprocess can cancel the timer while bypassing the process end listener, leaving the DSF start Task stale.
		assertEquals(1.0, (Double) xpath.evaluate(
				"count(//bpmn:boundaryEvent[@id='BoundaryEvent_stopSignal' and @attachedToRef='Activity_transferLoop' and @cancelActivity='true']/bpmn:signalEventDefinition[@signalRef='Signal_atmpDataTransferStop'])",
				document, XPathConstants.NUMBER), 0.0);
		assertEquals("EndEvent_atmpDataTransferStopped",
				xpath.evaluate("string(//bpmn:sequenceFlow[@id='Flow_stop_to_stopped']/@targetRef)", document));
		assertEquals(1.0,
				(Double) xpath.evaluate(
						"count(/bpmn:definitions/bpmn:process/bpmn:endEvent[@id='EndEvent_atmpDataTransferStopped'])",
						document, XPathConstants.NUMBER),
				0.0);
		assertEquals(0.0, (Double) xpath.evaluate(
				"count(//bpmn:sequenceFlow[@sourceRef='Event_timerInterval' and @targetRef='EndEvent_duplicateStartRejected'])",
				document, XPathConstants.NUMBER), 0.0);
	}

	private boolean exists(String file)
	{
		return getClass().getClassLoader().getResourceAsStream(file) != null;
	}

	private Document readProcessModel() throws Exception
	{
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		try (InputStream in = getClass().getClassLoader().getResourceAsStream("bpe/atmp-data-transfer.bpmn"))
		{
			assertNotNull(in);
			return factory.newDocumentBuilder().parse(in);
		}
	}
}
