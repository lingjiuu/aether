package io.github.lingjiuu.transcript;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.SystemMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.util.List;

public class TranscriptProjectorTest extends TestCase {

    public void testRestoreAppliesContentReplacementAndSnipBoundary() throws Exception {
        TranscriptStore store = new TranscriptStore(Files.createTempDirectory("aether-transcript-projector-test"));
        UserMessage keep = user("keep");
        UserMessage remove = user("remove");
        ToolResultMessage toolResult = ToolResultMessage.builder()
                .id("tool-message")
                .toolCallId("call-1")
                .toolName("test_tool")
                .contents(List.of(TextContent.builder().text("full tool output").build()))
                .build();
        SystemMessage replacement = SystemMessage.builder()
                .subtype(SystemMessage.Subtype.CONTENT_REPLACEMENT)
                .toolResultReplacements(List.of(new SystemMessage.ToolResultReplacement("call-1", "preview")))
                .contents(List.of(TextContent.builder().text("replacement marker").build()))
                .build();
        SystemMessage snip = SystemMessage.builder()
                .subtype(SystemMessage.Subtype.SNIP_BOUNDARY)
                .removedMessageIds(List.of(remove.id()))
                .contents(List.of(TextContent.builder().text("snip marker").build()))
                .build();

        append(store, "r1", null, keep);
        append(store, "r2", "r1", remove);
        append(store, "r3", "r2", toolResult);
        append(store, "r4", "r3", replacement);
        append(store, "r5", "r4", snip);

        RestoredTranscript restored = new TranscriptRestorer(store).restore("session-1");
        List<Message> messages = restored.getRuntimeState().snapshot();

        assertFalse(messages.stream().anyMatch(message -> message.id().equals(remove.id())));
        ToolResultMessage restoredTool = (ToolResultMessage) messages.stream()
                .filter(message -> message instanceof ToolResultMessage)
                .findFirst()
                .orElseThrow();
        assertEquals("preview", MessageContents.text(restoredTool));
    }

    private void append(TranscriptStore store, String id, String parentRecordId, Message message) {
        store.append(TranscriptRecord.builder()
                .id(id)
                .sessionId("session-1")
                .parentRecordId(parentRecordId)
                .turn(1)
                .timestamp(System.currentTimeMillis())
                .message(message)
                .build());
    }

    private UserMessage user(String text) {
        return UserMessage.builder()
                .contents(List.of(TextContent.builder().text(text).build()))
                .build();
    }
}
