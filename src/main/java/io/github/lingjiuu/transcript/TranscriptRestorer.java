package io.github.lingjiuu.transcript;

import io.github.lingjiuu.context.ContextBuilder;
import io.github.lingjiuu.context.EnvironmentContext;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.MessageContents;
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
        List<UiEvent> timelineEvents = new ArrayList<>(timelineEvents(records));
        List<Message> messages = new ArrayList<>(state.messages());
        long lastEventSequence = lastEventSequence(timelineEvents);

        InterruptedTurnBoundary interruptedBoundary = interruptedTurnBoundary(timelineEvents);
        if (interruptedBoundary != null) {
            if (!hasInterruptedTurnMessage(messages)) {
                messages.add(new ContextBuilder().interruptedTurnMessage());
            }
            UiEvent interruptedEvent = interruptedBoundary.toEvent(sessionId, lastEventSequence + 1);
            timelineEvents.add(interruptedEvent);
            lastEventSequence = interruptedEvent.getSequence() == null
                    ? lastEventSequence
                    : interruptedEvent.getSequence();
        }
        String lastRecordId = records.isEmpty() ? null : records.getLast().getId();
        return new TranscriptReconstruction(
                sessionId,
                sessionMeta,
                latestModelSelection(records, sessionMeta),
                sessionName == null ? null : sessionName.getName(),
                List.copyOf(messages),
                state.initialContextBaseline(),
                List.copyOf(timelineEvents),
                lastEventSequence,
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
        EnvironmentContext initialContextBaseline = null;
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

    private EnvironmentContext replay(
            List<Message> messages,
            EnvironmentContext initialContextBaseline,
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

    private boolean hasInterruptedTurnMessage(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        Message lastMessage = messages.getLast();
        if (!(lastMessage instanceof ContextMessage contextMessage)
                || contextMessage.getKind() != ContextMessage.ContextKind.INFORMATIONAL) {
            return false;
        }
        String text = MessageContents.text(lastMessage);
        return text.startsWith("<turn_aborted>") && text.endsWith("</turn_aborted>");
    }

    private InterruptedTurnBoundary interruptedTurnBoundary(List<UiEvent> timelineEvents) {
        UiEvent activeTurnStart = null;
        boolean turnOpen = false;
        for (UiEvent event : timelineEvents) {
            if (event == null || event.getType() == null) {
                continue;
            }
            switch (event.getType()) {
                case TURN_STARTED -> {
                    activeTurnStart = event;
                    turnOpen = true;
                }
                case TURN_COMPLETED, TURN_ABORTED -> {
                    activeTurnStart = null;
                    turnOpen = false;
                }
                default -> {
                }
            }
        }
        if (!turnOpen || activeTurnStart == null) {
            return null;
        }
        return new InterruptedTurnBoundary(activeTurnStart);
    }

    private record ReconstructionState(
            List<Message> messages,
            EnvironmentContext initialContextBaseline
    ) {
    }

    private record InterruptedTurnBoundary(UiEvent startedEvent) {
        private UiEvent toEvent(String sessionId, long sequence) {
            return UiEvent.builder()
                    .type(UiEventType.TURN_ABORTED)
                    .sessionId(sessionId)
                    .commandId(startedEvent.getCommandId())
                    .turnId(startedEvent.getTurnId())
                    .turn(startedEvent.getTurn())
                    .sequence(sequence)
                    .timestampMs(System.currentTimeMillis())
                    .build();
        }
    }
}
