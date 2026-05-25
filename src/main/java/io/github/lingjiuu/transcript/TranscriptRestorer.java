package io.github.lingjiuu.transcript;

import io.github.lingjiuu.context.InitialContextSnapshot;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventPayloads;
import io.github.lingjiuu.protocol.UiEventType;
import io.github.lingjiuu.protocol.UiModelSelection;
import io.github.lingjiuu.transcript.item.CompactedTranscriptItem;
import io.github.lingjiuu.transcript.item.EventTranscriptItem;
import io.github.lingjiuu.transcript.item.MessageTranscriptItem;
import io.github.lingjiuu.transcript.item.SessionMetaItem;
import io.github.lingjiuu.transcript.item.SessionNameItem;
import io.github.lingjiuu.transcript.item.TranscriptItem;
import io.github.lingjiuu.transcript.item.TurnContextItem;

import java.util.ArrayList;
import java.util.Comparator;
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
        SessionNameItem sessionName = null;
        for (TranscriptRecord record : records) {
            if (record == null || record.getItem() == null) {
                continue;
            }
            if (record.getItem() instanceof SessionMetaItem metaItem && sessionMeta == null) {
                sessionMeta = metaItem;
            }
            if (record.getItem() instanceof SessionNameItem nameItem
                    && nameItem.getName() != null
                    && !nameItem.getName().isBlank()) {
                sessionName = nameItem;
            }
        }

        ReconstructionState state = reconstructFromLatestCheckpoint(records);
        List<UiEvent> timelineEvents = timelineEvents(records);
        String lastRecordId = records.isEmpty() ? null : records.getLast().getId();
        return new TranscriptReconstruction(
                sessionId,
                sessionMeta,
                latestModelSelection(records, sessionMeta),
                sessionName == null ? null : sessionName.getName(),
                state.messages(),
                state.initialContextBaseline(),
                timelineEvents,
                lastEventSequence(timelineEvents),
                lastRecordId
        );
    }

    private TranscriptModelSelection latestModelSelection(List<TranscriptRecord> records, SessionMetaItem sessionMeta) {
        TranscriptModelSelection selection = sessionMeta == null
                ? null
                : new TranscriptModelSelection(
                        sessionMeta.getModelProvider(),
                        sessionMeta.getModelId(),
                        sessionMeta.getReasoningEffort()
                );
        for (TranscriptRecord record : records) {
            if (!(record.getItem() instanceof EventTranscriptItem eventItem)) {
                continue;
            }
            UiEvent event = eventItem.getEvent();
            if (event == null || event.getType() != UiEventType.MODEL_CHANGED) {
                continue;
            }
            if (event.getPayload() instanceof UiEventPayloads.ModelSelection modelPayload) {
                UiModelSelection modelSelection = modelPayload.modelSelection();
                if (modelSelection != null) {
                    selection = new TranscriptModelSelection(
                            modelSelection.providerId(),
                            modelSelection.modelId(),
                            modelSelection.reasoningEffort()
                    );
                }
            }
        }
        return selection;
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
        events.sort(Comparator.comparingLong(this::eventSequence));
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

    private long eventSequence(UiEvent event) {
        if (event == null || event.getSequence() == null) {
            return Long.MAX_VALUE;
        }
        return event.getSequence();
    }

    private record ReconstructionState(
            List<Message> messages,
            InitialContextSnapshot initialContextBaseline
    ) {
    }
}
