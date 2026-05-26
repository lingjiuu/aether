package io.github.lingjiuu.session.recording;

import io.github.lingjiuu.context.ContextManager;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.transcript.TranscriptRecorder;
import io.github.lingjiuu.transcript.item.TurnContextItem;

import java.util.List;

public class MessageRecorder {

    private final ContextManager contextManager;
    private final TranscriptRecorder transcriptRecorder;

    public MessageRecorder(ContextManager contextManager, TranscriptRecorder transcriptRecorder) {
        if (contextManager == null) {
            throw new IllegalArgumentException("contextManager must not be null");
        }
        this.contextManager = contextManager;
        this.transcriptRecorder = transcriptRecorder;
    }

    public synchronized void record(Message message, int turn) {
        if (message == null) {
            return;
        }
        contextManager.record(message);
        if (transcriptRecorder != null) {
            try {
                transcriptRecorder.record(message, turn);
            } catch (RuntimeException e) {
                throw new MessageRecordException("Failed to record transcript message.", e);
            }
        }
    }

    public synchronized void recordTurnContext(TurnContextItem turnContextItem) {
        if (turnContextItem == null || transcriptRecorder == null) {
            return;
        }
        try {
            transcriptRecorder.recordTurnContext(turnContextItem);
        } catch (RuntimeException e) {
            throw new MessageRecordException("Failed to record transcript turn context.", e);
        }
    }

    public synchronized void recordSessionName(String name) {
        if (name == null || name.isBlank() || transcriptRecorder == null) {
            return;
        }
        try {
            transcriptRecorder.recordSessionName(name);
        } catch (RuntimeException e) {
            throw new MessageRecordException("Failed to record transcript session name.", e);
        }
    }

    public synchronized void recordCompaction(
            String summary,
            List<Message> replacementMessages,
            int turn,
            int originalMessageCount,
            int preservedUserMessageCount
    ) {
        contextManager.replaceAll(replacementMessages);
        if (transcriptRecorder != null) {
            try {
                transcriptRecorder.recordCompaction(
                        summary,
                        replacementMessages,
                        turn,
                        originalMessageCount,
                        preservedUserMessageCount
                );
            } catch (RuntimeException e) {
                throw new MessageRecordException("Failed to record transcript compaction.", e);
            }
        }
    }

}
