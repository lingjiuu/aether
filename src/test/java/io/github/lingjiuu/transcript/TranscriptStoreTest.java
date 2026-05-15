package io.github.lingjiuu.transcript;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.ImageContent;
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

    public void testAppendAndReadRoundTripsImageContent() throws Exception {
        TranscriptStore store = new TranscriptStore(Files.createTempDirectory("aether-transcripts-image-test"));
        ToolResultMessage toolResult = ToolResultMessage.builder()
                .toolCallId("call-1")
                .toolName("read")
                .contents(List.of(
                        TextContent.builder().text("Read image file [image/png]").build(),
                        ImageContent.builder()
                                .data("abc123")
                                .mimeType("image/png")
                                .build()
                ))
                .build();

        store.append(TranscriptRecord.builder()
                .id("record-1")
                .sessionId("session-1")
                .turn(0)
                .timestamp(123L)
                .message(toolResult)
                .build());

        List<TranscriptRecord> records = store.read("session-1");

        assertEquals(1, records.size());
        ToolResultMessage restored = (ToolResultMessage) records.getFirst().getMessage();
        assertEquals("read", restored.getToolName());
        assertEquals(2, restored.getContents().size());
        assertTrue(restored.getContents().get(1) instanceof ImageContent);
        ImageContent image = (ImageContent) restored.getContents().get(1);
        assertEquals("image/png", image.getMimeType());
        assertEquals("abc123", image.getData());
    }
}
