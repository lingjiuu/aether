package io.github.lingjiuu.transcript;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.transcript.item.TurnContextItem;
import io.github.lingjiuu.transcript.item.CompactedTranscriptItem;
import io.github.lingjiuu.transcript.item.EventTranscriptItem;
import io.github.lingjiuu.transcript.item.MessageTranscriptItem;
import io.github.lingjiuu.transcript.item.SessionMetaItem;
import io.github.lingjiuu.transcript.item.SessionNameItem;
import io.github.lingjiuu.transcript.item.TranscriptItem;
import io.github.lingjiuu.transcript.item.ToolResultReplacementTranscriptItem;

import java.util.List;
import java.util.UUID;

public class TranscriptRecorder {

    private final TranscriptStore store;
    private final String sessionId;

    public TranscriptRecorder(TranscriptStore store, String sessionId) {
        this(store, sessionId, null);
    }

    public TranscriptRecorder(TranscriptStore store, String sessionId, String ignoredLastRecordId) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.store = store;
        this.sessionId = sessionId;
    }

    public synchronized TranscriptRecord record(Message message, int turn) {
        if (message == null) {
            return null;
        }
        return append(MessageTranscriptItem.builder()
                .message(message)
                .build(), turn);
    }

    public synchronized TranscriptRecord recordToolResultReplacement(
            ToolResultReplacementTranscriptItem item,
            int turn
    ) {
        if (item == null || item.getReplacementMessage() == null) {
            return null;
        }
        return append(item, turn);
    }

    public synchronized TranscriptRecord recordSessionMeta(SessionMetaItem sessionMeta) {
        if (sessionMeta == null) {
            return null;
        }
        return append(sessionMeta, 0);
    }

    public synchronized TranscriptRecord recordSessionName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return append(SessionNameItem.builder()
                .sessionId(sessionId)
                .name(name.trim())
                .build(), 0);
    }

    public synchronized TranscriptRecord recordTurnContext(TurnContextItem turnContextItem) {
        if (turnContextItem == null) {
            return null;
        }
        return append(turnContextItem, turnContextItem.getTurn());
    }

    public synchronized TranscriptRecord recordEvent(UiEvent event) {
        if (event == null) {
            return null;
        }
        return append(EventTranscriptItem.builder()
                .event(event)
                .build(), event.getTurn() == null ? 0 : event.getTurn());
    }

    public synchronized TranscriptRecord recordCompaction(
            String summary,
            List<Message> replacementMessages,
            int turn,
            int originalMessageCount,
            int preservedUserMessageCount
    ) {
        return append(CompactedTranscriptItem.builder()
                .summary(summary)
                .originalMessageCount(originalMessageCount)
                .replacementMessageCount(replacementMessages == null ? 0 : replacementMessages.size())
                .preservedUserMessageCount(preservedUserMessageCount)
                .replacementMessages(replacementMessages)
                .build(), turn);
    }

    private TranscriptRecord append(TranscriptItem item, int turn) {
        TranscriptRecord record = TranscriptRecord.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .turn(turn)
                .timestamp(System.currentTimeMillis())
                .item(item)
                .build();
        store.append(record);
        return record;
    }
}
