package io.github.lingjiuu.recording;

import io.github.lingjiuu.context.ContextManager;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.transcript.TranscriptRecorder;

import java.util.Collection;
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

    public synchronized void recordAll(Collection<? extends Message> messages, int turn) {
        if (messages == null) {
            return;
        }
        for (Message message : messages) {
            record(message, turn);
        }
    }

    public synchronized void replaceActiveMessages(Collection<? extends Message> messages) {
        contextManager.replaceAll(messages);
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

    public synchronized void clear() {
        contextManager.clear();
    }

}
