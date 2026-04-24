package io.github.lingjiuu.transcript;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.provider.openai.OpenAiReplayData;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.util.List;

public class TranscriptStoreTest extends TestCase {

    public void testAppendAndReadRoundTripsPolymorphicMessage() throws Exception {
        TranscriptStore store = new TranscriptStore(Files.createTempDirectory("aether-transcripts-test"));
        UserMessage userMessage = UserMessage.builder()
                .contents(List.of(TextContent.builder().text("Hello").build()))
                .build();

        store.append(TranscriptRecord.builder()
                .id("record-1")
                .sessionId("session-1")
                .turn(0)
                .timestamp(123L)
                .message(userMessage)
                .build());
        store.append(TranscriptRecord.builder()
                .id("record-2")
                .sessionId("session-1")
                .parentRecordId("record-1")
                .turn(1)
                .timestamp(124L)
                .message(AssistantMessage.builder()
                        .contents(List.of(TextContent.builder().text("Done.").build()))
                        .providerState(OpenAiReplayData.builder()
                                .responseId("response-1")
                                .items(List.of(OpenAiReplayData.ReplayItem.builder()
                                        .type(OpenAiReplayData.Type.OUTPUT_MESSAGE)
                                        .json("{}")
                                        .build()))
                                .build())
                        .stopReason(AssistantMessage.StopReason.STOP)
                        .build())
                .build());

        List<TranscriptRecord> records = store.read("session-1");

        assertTrue(store.exists("session-1"));
        assertEquals(2, records.size());
        assertEquals("record-1", records.getFirst().getId());
        assertEquals("session-1", records.getFirst().getSessionId());
        assertEquals(0, records.getFirst().getTurn());
        assertEquals(Message.Role.USER, records.getFirst().getMessage().role());
        assertEquals("Hello", MessageContents.text(records.getFirst().getMessage()));
        assertEquals("record-1", records.get(1).getParentRecordId());
        assertEquals("Done.", MessageContents.text(records.get(1).getMessage()));
        assertTrue(((AssistantMessage) records.get(1).getMessage()).getProviderState() instanceof OpenAiReplayData);
    }
}
