package io.github.lingjiuu.transcript;

import io.github.lingjiuu.context.EnvironmentContext;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.transcript.item.CompactedTranscriptItem;
import io.github.lingjiuu.transcript.item.EventTranscriptItem;
import io.github.lingjiuu.transcript.item.MessageTranscriptItem;
import io.github.lingjiuu.transcript.item.TurnContextItem;
import io.github.lingjiuu.transcript.item.ToolResultReplacementTranscriptItem;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventType;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public class TranscriptRestorerTest extends TestCase {

    public void testRestoreUsesLatestCompactionCheckpointAndReplaysSuffix() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        TranscriptStore store = new TranscriptStore(Files.createTempDirectory("aether-transcript-test"));
        EnvironmentContext oldBaseline = environmentContext("/tmp/old");
        EnvironmentContext restoredBaseline = environmentContext("/tmp/new");

        append(store, sessionId, messageItem("old message"), 1);
        append(store, sessionId, turnContextItem("turn-1", 1, oldBaseline), 1);
        append(store, sessionId, compactedItem("summary", List.of(userMessage("summary message"))), 2);
        append(store, sessionId, turnContextItem("turn-2", 2, restoredBaseline), 2);
        append(store, sessionId, messageItem("suffix message"), 2);

        TranscriptReconstruction reconstruction = new TranscriptRestorer(store).restore(sessionId);

        assertEquals(2, reconstruction.messages().size());
        assertEquals("summary message", MessageContents.text(reconstruction.messages().get(0)));
        assertEquals("suffix message", MessageContents.text(reconstruction.messages().get(1)));
        assertEquals(restoredBaseline, reconstruction.initialContextBaseline());
    }

    public void testManualCompactionClearsEarlierTurnContextBaseline() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        TranscriptStore store = new TranscriptStore(Files.createTempDirectory("aether-transcript-test"));

        append(store, sessionId, turnContextItem("turn-1", 1, environmentContext("/tmp/old")), 1);
        append(store, sessionId, compactedItem("summary", List.of(userMessage("summary message"))), 2);

        TranscriptReconstruction reconstruction = new TranscriptRestorer(store).restore(sessionId);

        assertEquals(1, reconstruction.messages().size());
        assertEquals("summary message", MessageContents.text(reconstruction.messages().getFirst()));
        assertNull(reconstruction.initialContextBaseline());
    }

    public void testRestoreReplaysPersistedOutputPreviewWithoutRetrimming() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        TranscriptStore store = new TranscriptStore(Files.createTempDirectory("aether-transcript-test"));
        String preview = """
                <persisted-output>
                Output too large (60.0KB). Full output saved to: /missing/tool-results/call-1.txt

                Preview (first 2.0KB):
                hello
                ...
                </persisted-output>""";

        append(store, sessionId, MessageTranscriptItem.builder()
                .message(ToolResultMessage.builder()
                        .toolCallId("call-1")
                        .toolName("bash")
                        .contents(List.of(TextContent.builder().text(preview).build()))
                        .build())
                .build(), 1);

        TranscriptReconstruction reconstruction = new TranscriptRestorer(store).restore(sessionId);

        assertEquals(1, reconstruction.messages().size());
        assertTrue(reconstruction.messages().getFirst() instanceof ToolResultMessage);
        assertEquals(preview, MessageContents.text(reconstruction.messages().getFirst()));
    }

    public void testRestoreReturnsTimelineEventsWithoutAffectingMessages() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        TranscriptStore store = new TranscriptStore(Files.createTempDirectory("aether-transcript-test"));

        append(store, sessionId, eventItem(UiEventType.TURN_COMPLETED, 11), 1);
        append(store, sessionId, messageItem("message"), 1);
        append(store, sessionId, eventItem(UiEventType.TURN_STARTED, 10), 1);

        TranscriptReconstruction reconstruction = new TranscriptRestorer(store).restore(sessionId);

        assertEquals(1, reconstruction.messages().size());
        assertEquals("message", MessageContents.text(reconstruction.messages().getFirst()));
        assertEquals(2, reconstruction.timelineEvents().size());
        assertEquals(UiEventType.TURN_STARTED, reconstruction.timelineEvents().getFirst().getType());
        assertEquals(UiEventType.TURN_COMPLETED, reconstruction.timelineEvents().getLast().getType());
        assertEquals(11, reconstruction.lastEventSequence());
    }

    public void testRestoreAppliesAppendOnlyToolResultReplacement() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        TranscriptStore store = new TranscriptStore(Files.createTempDirectory("aether-transcript-test"));
        ToolResultMessage original = toolResult("message-1", "call-1", "batch-1", "raw output");
        ToolResultMessage replacement = toolResult("message-1", "call-1", "batch-1", "preview output");

        append(store, sessionId, MessageTranscriptItem.builder()
                .message(original)
                .build(), 1);
        append(store, sessionId, ToolResultReplacementTranscriptItem.builder()
                .messageId("message-1")
                .toolCallId("call-1")
                .toolBatchId("batch-1")
                .replacementMessage(replacement)
                .build(), 1);

        TranscriptReconstruction reconstruction = new TranscriptRestorer(store).restore(sessionId);

        assertEquals(1, reconstruction.messages().size());
        assertTrue(reconstruction.messages().getFirst() instanceof ToolResultMessage);
        ToolResultMessage restored = (ToolResultMessage) reconstruction.messages().getFirst();
        assertEquals("message-1", restored.getId());
        assertEquals("batch-1", restored.getToolBatchId());
        assertEquals("preview output", MessageContents.text(restored));
    }

    public void testRestoreSynthesizesInterruptedBoundaryForDanglingTurn() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        TranscriptStore store = new TranscriptStore(Files.createTempDirectory("aether-transcript-test"));

        append(store, sessionId, eventItem(UiEventType.TURN_STARTED, 11, "turn-1", 1), 1);
        append(store, sessionId, messageItem("partial message"), 1);

        TranscriptReconstruction reconstruction = new TranscriptRestorer(store).restore(sessionId);

        assertEquals(2, reconstruction.messages().size());
        assertTrue(reconstruction.messages().getLast() instanceof ContextMessage);
        ContextMessage interrupted = (ContextMessage) reconstruction.messages().getLast();
        assertEquals(ContextMessage.ContextKind.INFORMATIONAL, interrupted.getKind());
        String text = MessageContents.text(interrupted);
        assertTrue(text.startsWith("<turn_aborted>"));
        assertTrue(text.endsWith("</turn_aborted>"));

        assertEquals(2, reconstruction.timelineEvents().size());
        assertEquals(UiEventType.TURN_STARTED, reconstruction.timelineEvents().getFirst().getType());
        assertEquals(UiEventType.TURN_ABORTED, reconstruction.timelineEvents().getLast().getType());
        assertEquals("turn-1", reconstruction.timelineEvents().getLast().getTurnId());
        assertEquals(1, reconstruction.timelineEvents().getLast().getTurn().intValue());
        assertEquals(12, reconstruction.lastEventSequence());
    }

    private MessageTranscriptItem messageItem(String text) {
        return MessageTranscriptItem.builder()
                .message(userMessage(text))
                .build();
    }

    private UserMessage userMessage(String text) {
        return UserMessage.builder()
                .contents(List.of(TextContent.builder()
                        .text(text)
                        .build()))
                .build();
    }

    private ToolResultMessage toolResult(String id, String toolCallId, String toolBatchId, String text) {
        return ToolResultMessage.builder()
                .id(id)
                .toolCallId(toolCallId)
                .toolBatchId(toolBatchId)
                .toolName("bash")
                .contents(List.of(TextContent.builder()
                        .text(text)
                        .build()))
                .build();
    }

    private TurnContextItem turnContextItem(
            String turnId,
            int turn,
            EnvironmentContext initialContextBaseline
    ) {
        return TurnContextItem.builder()
                .turnId(turnId)
                .turn(turn)
                .initialContextBaseline(initialContextBaseline)
                .build();
    }

    private CompactedTranscriptItem compactedItem(String summary, List<UserMessage> replacementMessages) {
        return CompactedTranscriptItem.builder()
                .summary(summary)
                .originalMessageCount(10)
                .replacementMessageCount(replacementMessages.size())
                .preservedUserMessageCount(1)
                .replacementMessages(List.copyOf(replacementMessages))
                .build();
    }

    private EventTranscriptItem eventItem(UiEventType type, long sequence) {
        return eventItem(type, sequence, null, null);
    }

    private EventTranscriptItem eventItem(UiEventType type, long sequence, String turnId, Integer turn) {
        return EventTranscriptItem.builder()
                .event(UiEvent.builder()
                        .type(type)
                        .turnId(turnId)
                        .turn(turn)
                        .sequence(sequence)
                        .timestampMs(System.currentTimeMillis())
                        .build())
                .build();
    }

    private EnvironmentContext environmentContext(String cwd) {
        return new EnvironmentContext(
                Path.of(cwd),
                "zsh",
                LocalDate.parse("2026-05-20"),
                ZoneId.of("UTC")
        );
    }

    private void append(
            TranscriptStore store,
            String sessionId,
            io.github.lingjiuu.transcript.item.TranscriptItem item,
            int turn
    ) {
        store.append(TranscriptRecord.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .turn(turn)
                .timestamp(System.currentTimeMillis())
                .item(item)
                .build());
    }
}
