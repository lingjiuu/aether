package io.github.lingjiuu.transcript;

import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.TextContent;
import junit.framework.TestCase;

import java.util.List;

public class TranscriptChainBuilderTest extends TestCase {

    public void testBuildFollowsLatestLeafBackToRoot() {
        TranscriptRecord root = userRecord("root", null, "Root");
        TranscriptRecord oldLeaf = assistantRecord("old-leaf", "root", "Old branch");
        TranscriptRecord newLeaf = assistantRecord("new-leaf", "root", "New branch");

        TranscriptChain chain = new TranscriptChainBuilder().build(List.of(root, oldLeaf, newLeaf));

        assertEquals(2, chain.records().size());
        assertEquals("root", chain.records().getFirst().getId());
        assertEquals("new-leaf", chain.records().get(1).getId());
        assertEquals(List.of("Root", "New branch"), chain.messages().stream()
                .map(MessageContents::text)
                .toList());
        assertEquals("new-leaf", chain.lastRecordId());
    }

    private TranscriptRecord userRecord(String id, String parentRecordId, String text) {
        return TranscriptRecord.builder()
                .id(id)
                .sessionId("session-1")
                .parentRecordId(parentRecordId)
                .turn(0)
                .timestamp(System.currentTimeMillis())
                .message(UserMessage.builder()
                        .contents(List.of(TextContent.builder().text(text).build()))
                        .build())
                .build();
    }

    private TranscriptRecord assistantRecord(String id, String parentRecordId, String text) {
        return TranscriptRecord.builder()
                .id(id)
                .sessionId("session-1")
                .parentRecordId(parentRecordId)
                .turn(1)
                .timestamp(System.currentTimeMillis())
                .message(AssistantMessage.builder()
                        .contents(List.of(TextContent.builder().text(text).build()))
                        .stopReason(AssistantMessage.StopReason.STOP)
                        .build())
                .build();
    }
}
