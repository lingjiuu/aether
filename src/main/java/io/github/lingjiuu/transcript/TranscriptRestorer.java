package io.github.lingjiuu.transcript;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.transcript.item.CompactedTranscriptItem;
import io.github.lingjiuu.transcript.item.MessageTranscriptItem;
import io.github.lingjiuu.transcript.item.SessionMetaItem;
import io.github.lingjiuu.transcript.item.TranscriptItem;

import java.util.ArrayList;
import java.util.List;

public class TranscriptRestorer {

    private final TranscriptStore store;

    public TranscriptRestorer(TranscriptStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
    }

    public TranscriptReconstruction restore(String sessionId) {
        List<Message> messages = new ArrayList<>();
        SessionMetaItem sessionMeta = null;
        String lastRecordId = null;
        for (TranscriptRecord record : store.read(sessionId)) {
            if (record == null || record.getItem() == null) {
                continue;
            }
            if (record.getItem() instanceof SessionMetaItem metaItem && sessionMeta == null) {
                sessionMeta = metaItem;
            } else {
                replay(messages, record.getItem());
            }
            lastRecordId = record.getId();
        }
        return new TranscriptReconstruction(
                sessionId,
                sessionMeta,
                messages,
                lastRecordId
        );
    }

    private void replay(List<Message> messages, TranscriptItem item) {
        if (item instanceof MessageTranscriptItem messageItem) {
            if (messageItem.getMessage() != null) {
                messages.add(messageItem.getMessage());
            }
            return;
        }
        if (item instanceof CompactedTranscriptItem compactedItem) {
            messages.clear();
            if (compactedItem.getReplacementMessages() != null) {
                messages.addAll(compactedItem.getReplacementMessages());
            }
        }
    }
}
