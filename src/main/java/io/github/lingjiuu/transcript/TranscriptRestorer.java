package io.github.lingjiuu.transcript;

import io.github.lingjiuu.context.InitialContextSnapshot;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.transcript.item.CompactedTranscriptItem;
import io.github.lingjiuu.transcript.item.EventTranscriptItem;
import io.github.lingjiuu.transcript.item.MessageTranscriptItem;
import io.github.lingjiuu.transcript.item.SessionMetaItem;
import io.github.lingjiuu.transcript.item.TranscriptItem;
import io.github.lingjiuu.transcript.item.TurnContextItem;

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
        List<TranscriptRecord> records = store.read(sessionId);
        SessionMetaItem sessionMeta = null;
        for (TranscriptRecord record : records) {
            if (record == null || record.getItem() == null) {
                continue;
            }
            if (record.getItem() instanceof SessionMetaItem metaItem && sessionMeta == null) {
                sessionMeta = metaItem;
            }
        }

        ReconstructionState state = reconstructFromLatestCheckpoint(records);
        List<UiEvent> timelineEvents = timelineEvents(records);
        String lastRecordId = records.isEmpty() ? null : records.getLast().getId();
        return new TranscriptReconstruction(
                sessionId,
                sessionMeta,
                state.messages(),
                state.initialContextBaseline(),
                timelineEvents,
                lastEventSequence(timelineEvents),
                lastRecordId
        );
    }

    private ReconstructionState reconstructFromLatestCheckpoint(List<TranscriptRecord> records) {
        int compactIndex = latestCompactionIndex(records);
        List<Message> messages = new ArrayList<>();
        InitialContextSnapshot initialContextBaseline = null;
        int startIndex = 0;

        if (compactIndex >= 0) {
            CompactedTranscriptItem compactedItem = (CompactedTranscriptItem) records.get(compactIndex).getItem();
            if (compactedItem.getReplacementMessages() != null) {
                messages.addAll(compactedItem.getReplacementMessages());
            }
            startIndex = compactIndex + 1;
        }

        for (int index = startIndex; index < records.size(); index++) {
            TranscriptRecord record = records.get(index);
            if (record == null || record.getItem() == null) {
                continue;
            }
            initialContextBaseline = replay(messages, initialContextBaseline, record.getItem());
        }

        return new ReconstructionState(List.copyOf(messages), initialContextBaseline);
    }

    private int latestCompactionIndex(List<TranscriptRecord> records) {
        for (int index = records.size() - 1; index >= 0; index--) {
            TranscriptRecord record = records.get(index);
            if (record != null && record.getItem() instanceof CompactedTranscriptItem) {
                return index;
            }
        }
        return -1;
    }

    private InitialContextSnapshot replay(
            List<Message> messages,
            InitialContextSnapshot initialContextBaseline,
            TranscriptItem item
    ) {
        if (item instanceof MessageTranscriptItem messageItem) {
            if (messageItem.getMessage() != null) {
                messages.add(messageItem.getMessage());
            }
            return initialContextBaseline;
        }
        if (item instanceof CompactedTranscriptItem compactedItem) {
            messages.clear();
            if (compactedItem.getReplacementMessages() != null) {
                messages.addAll(compactedItem.getReplacementMessages());
            }
            return null;
        }
        if (item instanceof TurnContextItem turnContextItem) {
            return turnContextItem.getInitialContextBaseline();
        }
        return initialContextBaseline;
    }

    private List<UiEvent> timelineEvents(List<TranscriptRecord> records) {
        List<UiEvent> events = new ArrayList<>();
        for (TranscriptRecord record : records) {
            if (record == null || record.getItem() == null) {
                continue;
            }
            if (record.getItem() instanceof EventTranscriptItem eventItem && eventItem.getEvent() != null) {
                events.add(eventItem.getEvent());
            }
        }
        return List.copyOf(events);
    }

    private long lastEventSequence(List<UiEvent> events) {
        long lastSequence = 0;
        for (UiEvent event : events) {
            if (event != null && event.getSequence() != null) {
                lastSequence = Math.max(lastSequence, event.getSequence());
            }
        }
        return lastSequence;
    }

    private record ReconstructionState(
            List<Message> messages,
            InitialContextSnapshot initialContextBaseline
    ) {
    }
}
