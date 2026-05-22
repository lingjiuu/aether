package io.github.lingjiuu.transcript;

import io.github.lingjiuu.context.EnvironmentContext;
import io.github.lingjiuu.context.InitialContextSnapshot;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.transcript.item.CompactedTranscriptItem;
import io.github.lingjiuu.transcript.item.EventTranscriptItem;
import io.github.lingjiuu.transcript.item.MessageTranscriptItem;
import io.github.lingjiuu.transcript.item.TurnContextItem;
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
        InitialContextSnapshot oldBaseline = snapshot("/tmp/old");
        InitialContextSnapshot restoredBaseline = snapshot("/tmp/new");

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

        append(store, sessionId, turnContextItem("turn-1", 1, snapshot("/tmp/old")), 1);
        append(store, sessionId, compactedItem("summary", List.of(userMessage("summary message"))), 2);

        TranscriptReconstruction reconstruction = new TranscriptRestorer(store).restore(sessionId);

        assertEquals(1, reconstruction.messages().size());
        assertEquals("summary message", MessageContents.text(reconstruction.messages().getFirst()));
        assertNull(reconstruction.initialContextBaseline());
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

    private TurnContextItem turnContextItem(
            String turnId,
            int turn,
            InitialContextSnapshot initialContextBaseline
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
        return EventTranscriptItem.builder()
                .event(UiEvent.builder()
                        .type(type)
                        .sequence(sequence)
                        .timestampMs(System.currentTimeMillis())
                        .build())
                .build();
    }

    private InitialContextSnapshot snapshot(String cwd) {
        return new InitialContextSnapshot(new EnvironmentContext(
                Path.of(cwd),
                LocalDate.parse("2026-05-20"),
                ZoneId.of("UTC")
        ));
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
