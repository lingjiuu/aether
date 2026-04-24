package io.github.lingjiuu.provider.protocol;

import io.github.lingjiuu.provider.ProviderReplayData;
import junit.framework.TestCase;

import java.util.List;

public class DefaultRequestFinalizerTest extends TestCase {

    public void testAddsSyntheticResultForMissingToolCallResult() {
        DefaultRequestFinalizer finalizer = new DefaultRequestFinalizer();

        List<NormalizedRequestMessage> messages = finalizer.finalizeMessages(List.of(
                new NormalizedAssistantMessage(List.of(
                        new NormalizedToolCallContent("call-1", "get_time", "{}")
                ), null)
        ));

        assertEquals(2, messages.size());
        assertEquals(NormalizedRequestMessage.Kind.ASSISTANT, messages.get(0).kind());
        assertEquals(NormalizedRequestMessage.Kind.CONTEXT, messages.get(1).kind());

        NormalizedToolResultContent syntheticResult =
                (NormalizedToolResultContent) messages.get(1).contents().getFirst();
        assertEquals("call-1", syntheticResult.toolCallId());
        assertTrue(syntheticResult.error());
        assertEquals("Tool execution did not return a result.", syntheticResult.outputText());
    }

    public void testAddsSyntheticResultToFollowingContextMessage() {
        DefaultRequestFinalizer finalizer = new DefaultRequestFinalizer();

        List<NormalizedRequestMessage> messages = finalizer.finalizeMessages(List.of(
                new NormalizedAssistantMessage(List.of(
                        new NormalizedToolCallContent("call-1", "get_time", "{}")
                ), null),
                new NormalizedContextMessage(List.of(
                        new NormalizedTextContent("host context")
                ))
        ));

        assertEquals(2, messages.size());
        assertEquals(NormalizedRequestMessage.Kind.CONTEXT, messages.get(1).kind());
        assertEquals(2, messages.get(1).contents().size());
        assertEquals("host context", ((NormalizedTextContent) messages.get(1).contents().get(0)).text());

        NormalizedToolResultContent syntheticResult =
                (NormalizedToolResultContent) messages.get(1).contents().get(1);
        assertEquals("call-1", syntheticResult.toolCallId());
        assertTrue(syntheticResult.error());
    }

    public void testStripsOrphanToolResultButKeepsTextContext() {
        DefaultRequestFinalizer finalizer = new DefaultRequestFinalizer();

        List<NormalizedRequestMessage> messages = finalizer.finalizeMessages(List.of(
                new NormalizedContextMessage(List.of(
                        new NormalizedToolResultContent("orphan", "get_time", "ignored", false, null),
                        new NormalizedTextContent("kept context")
                ))
        ));

        assertEquals(1, messages.size());
        assertEquals(NormalizedRequestMessage.Kind.CONTEXT, messages.getFirst().kind());
        assertEquals(1, messages.getFirst().contents().size());
        assertEquals("kept context", ((NormalizedTextContent) messages.getFirst().contents().getFirst()).text());
    }

    public void testPreservesContextAfterReplayBackedAssistant() {
        DefaultRequestFinalizer finalizer = new DefaultRequestFinalizer();

        List<NormalizedRequestMessage> messages = finalizer.finalizeMessages(List.of(
                new NormalizedAssistantMessage(List.of(
                        new NormalizedToolCallContent("call-fallback", "fallback_tool", "{}")
                ), new StubReplayData()),
                new NormalizedContextMessage(List.of(
                        new NormalizedToolResultContent("call-replay", "get_time", "{\"time\":\"12:00\"}", false, null)
                ))
        ));

        assertEquals(2, messages.size());
        assertEquals(NormalizedRequestMessage.Kind.ASSISTANT, messages.get(0).kind());
        assertEquals(NormalizedRequestMessage.Kind.CONTEXT, messages.get(1).kind());
        NormalizedToolResultContent toolResult =
                (NormalizedToolResultContent) messages.get(1).contents().getFirst();
        assertEquals("call-replay", toolResult.toolCallId());
        assertEquals("{\"time\":\"12:00\"}", toolResult.outputText());
    }

    private record StubReplayData() implements ProviderReplayData {

        @Override
        public String provider() {
            return "stub";
        }
    }
}
