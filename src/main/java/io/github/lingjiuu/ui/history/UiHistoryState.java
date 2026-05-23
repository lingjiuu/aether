package io.github.lingjiuu.ui.history;

import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventPayload;
import io.github.lingjiuu.protocol.UiEventPayloads;
import io.github.lingjiuu.protocol.UiHistory;
import io.github.lingjiuu.protocol.UiHistoryItem;
import io.github.lingjiuu.protocol.UiItem;
import io.github.lingjiuu.protocol.UiItemBodies;
import io.github.lingjiuu.protocol.UiItemKind;
import io.github.lingjiuu.protocol.UiToolCall;
import io.github.lingjiuu.protocol.UiToolResult;
import io.github.lingjiuu.protocol.UiToolUpdate;
import io.github.lingjiuu.protocol.UiTurn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UiHistoryState {

    private static final String RUNNING = "RUNNING";
    private static final String COMPLETED = "COMPLETED";
    private static final String ABORTED = "ABORTED";

    private final String sessionId;
    private final Map<String, TurnState> turns = new LinkedHashMap<>();

    private UiHistoryState(String sessionId) {
        this.sessionId = sessionId;
    }

    public static UiHistory fromEvents(String sessionId, List<UiEvent> events) {
        UiHistoryState state = new UiHistoryState(sessionId);
        if (events != null) {
            events.forEach(state::apply);
        }
        return state.toHistory();
    }

    public void apply(UiEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }
        switch (event.getType()) {
            case TURN_STARTED -> turn(event).status = RUNNING;
            case TURN_COMPLETED -> turn(event).status = COMPLETED;
            case TURN_ABORTED -> turn(event).status = ABORTED;
            case USER_MESSAGE -> applyUserMessage(event);
            case CONTEXT_MESSAGE -> applyContextMessage(event);
            case ITEM_STARTED -> applyItemStarted(event);
            case ASSISTANT_TEXT_DELTA, REASONING_DELTA -> applyTextDelta(event);
            case ITEM_COMPLETED -> applyItemCompleted(event);
            case TOOL_CALL_ARGUMENTS_DELTA -> applyToolArgumentsDelta(event);
            case TOOL_CALL_ARGUMENTS_DONE -> applyToolArgumentsDone(event);
            case TOOL_CALL -> applyToolCall(event);
            case TOOL_EXECUTION_BEGIN, TOOL_EXECUTION_UPDATE, TOOL_EXECUTION_END -> applyToolExecution(event);
            case TOOL_RESULT -> applyToolResult(event);
            case COMPACT_STARTED -> addTextItem(event, "compact-" + safeSequence(event), UiItemKind.CONTEXT_MESSAGE, RUNNING, compactText(event));
            case COMPACT_FINISHED -> addTextItem(event, "compact-" + safeSequence(event), UiItemKind.CONTEXT_MESSAGE, COMPLETED, compactText(event));
            case COMPACT_SKIPPED -> addTextItem(event, "compact-" + safeSequence(event), UiItemKind.CONTEXT_MESSAGE, "SKIPPED", compactText(event));
            case ERROR -> addTextItem(event, "error-" + safeSequence(event), UiItemKind.CONTEXT_MESSAGE, "ERROR", errorText(event));
            default -> {
            }
        }
    }

    public UiHistory toHistory() {
        List<UiTurn> renderedTurns = new ArrayList<>();
        for (TurnState turn : turns.values()) {
            renderedTurns.add(new UiTurn(
                    turn.turnId,
                    turn.commandId,
                    turn.turn,
                    turn.status,
                    turn.items.values()
                            .stream()
                            .map(ItemState::toItem)
                            .toList()
            ));
        }
        return new UiHistory(sessionId, renderedTurns);
    }

    private void applyUserMessage(UiEvent event) {
        if (event.getPayload() instanceof UiEventPayloads.UserMessage payload) {
            upsertItem(event, itemState(payload.item(), COMPLETED));
        }
    }

    private void applyContextMessage(UiEvent event) {
        if (event.getPayload() instanceof UiEventPayloads.ContextMessage payload) {
            upsertItem(event, itemState(payload.item(), COMPLETED));
        }
    }

    private void applyItemStarted(UiEvent event) {
        if (!(event.getPayload() instanceof UiEventPayloads.ItemStarted payload)
                || payload.itemId() == null
                || payload.itemKind() == null) {
            return;
        }
        ItemState item = item(event, payload.itemId(), payload.itemKind(), payload.contentIndex());
        item.status = RUNNING;
        item.toolCall = payload.toolCall();
    }

    private void applyTextDelta(UiEvent event) {
        if (!(event.getPayload() instanceof UiEventPayloads.TextDelta payload)
                || payload.itemId() == null
                || payload.itemKind() == null
                || payload.delta() == null) {
            return;
        }
        ItemState item = item(event, payload.itemId(), payload.itemKind(), payload.contentIndex());
        item.status = RUNNING;
        item.text = (item.text == null ? "" : item.text) + payload.delta();
    }

    private void applyItemCompleted(UiEvent event) {
        if (!(event.getPayload() instanceof UiEventPayloads.ItemCompleted payload) || payload.item() == null) {
            return;
        }
        ItemState item = itemState(payload.item(), COMPLETED);
        upsertItem(event, item);
    }

    private void applyToolArgumentsDelta(UiEvent event) {
        if (!(event.getPayload() instanceof UiEventPayloads.ToolArgumentsDelta payload)
                || payload.itemId() == null) {
            return;
        }
        ItemState item = item(event, payload.itemId(), UiItemKind.TOOL_CALL, payload.contentIndex());
        item.toolCall = payload.toolCall();
        item.status = RUNNING;
    }

    private void applyToolArgumentsDone(UiEvent event) {
        if (!(event.getPayload() instanceof UiEventPayloads.ToolArgumentsDone payload) || payload.item() == null) {
            return;
        }
        upsertItem(event, itemState(payload.item(), COMPLETED));
    }

    private void applyToolCall(UiEvent event) {
        if (!(event.getPayload() instanceof UiEventPayloads.ToolCall payload) || payload.toolCall() == null) {
            return;
        }
        UiToolCall toolCall = payload.toolCall();
        String itemId = toolCall.getItemId() == null ? toolCall.getToolCallId() : toolCall.getItemId();
        if (itemId == null) {
            return;
        }
        ItemState item = item(event, itemId, UiItemKind.TOOL_CALL, toolCall.getContentIndex());
        item.toolCall = toolCall;
    }

    private void applyToolExecution(UiEvent event) {
        if (!(event.getPayload() instanceof UiEventPayloads.ToolExecution payload) || payload.toolCall() == null) {
            return;
        }
        UiToolCall toolCall = payload.toolCall();
        String itemId = toolCall.getItemId() == null ? toolCall.getToolCallId() : toolCall.getItemId();
        if (itemId == null) {
            return;
        }
        ItemState item = item(event, itemId, UiItemKind.TOOL_CALL, toolCall.getContentIndex());
        item.toolCall = toolCall;
        UiToolUpdate update = payload.toolUpdate();
        item.toolUpdate = update;
        item.status = update == null || update.getStatus() == null ? RUNNING : update.getStatus();
    }

    private void applyToolResult(UiEvent event) {
        if (!(event.getPayload() instanceof UiEventPayloads.ToolResult payload) || payload.item() == null) {
            return;
        }
        ItemState resultItem = itemState(payload.item(), COMPLETED);
        if (resultItem == null || resultItem.toolResult == null) {
            return;
        }
        String sourceItemId = resultItem.toolResult.getSourceItemId();
        if (sourceItemId != null && !sourceItemId.isBlank()) {
            ItemState sourceItem = turn(event).items.get(sourceItemId);
            if (sourceItem != null) {
                sourceItem.toolResult = resultItem.toolResult;
                sourceItem.status = resultItem.toolResult.getStatus() == null
                        ? COMPLETED
                        : resultItem.toolResult.getStatus();
                return;
            }
        }
        upsertItem(event, resultItem);
    }

    private void addTextItem(
            UiEvent event,
            String id,
            UiItemKind kind,
            String status,
            String text
    ) {
        if (text == null || text.isBlank()) {
            return;
        }
        ItemState item = item(event, id, kind, null);
        item.status = status;
        item.text = text;
    }

    private void upsertItem(UiEvent event, ItemState item) {
        if (item == null || item.id == null) {
            return;
        }
        turn(event).items.put(item.id, item);
    }

    private ItemState item(UiEvent event, String itemId, UiItemKind kind, Integer contentIndex) {
        TurnState turn = turn(event);
        ItemState existing = turn.items.get(itemId);
        if (existing != null) {
            return existing;
        }
        ItemState created = new ItemState(itemId, kind, contentIndex);
        turn.items.put(itemId, created);
        return created;
    }

    private ItemState itemState(UiItem item, String status) {
        if (item == null || item.getItemId() == null) {
            return null;
        }
        ItemState state = new ItemState(item.getItemId(), item.getKind(), item.getContentIndex());
        state.status = status;
        if (item.getBody() instanceof UiItemBodies.Text text) {
            state.text = text.text();
        } else if (item.getBody() instanceof UiItemBodies.ToolCall toolCall) {
            state.toolCall = toolCall.toolCall();
        } else if (item.getBody() instanceof UiItemBodies.ToolResult toolResult) {
            state.toolResult = toolResult.toolResult();
            if (toolResult.toolResult() != null && toolResult.toolResult().getStatus() != null) {
                state.status = toolResult.toolResult().getStatus();
            }
        }
        return state;
    }

    private TurnState turn(UiEvent event) {
        String turnId = turnId(event);
        TurnState turn = turns.computeIfAbsent(
                turnId,
                ignored -> new TurnState(turnId, event.getTurn() == null ? 0 : event.getTurn())
        );
        if (turn.commandId == null && event.getCommandId() != null && !event.getCommandId().isBlank()) {
            turn.commandId = event.getCommandId();
        }
        return turn;
    }

    private String turnId(UiEvent event) {
        if (event.getTurnId() != null && !event.getTurnId().isBlank()) {
            return event.getTurnId();
        }
        return "turn-" + (event.getTurn() == null ? 0 : event.getTurn());
    }

    private String compactText(UiEvent event) {
        if (event.getPayload() instanceof UiEventPayloads.Compact compact) {
            return compact.text();
        }
        return null;
    }

    private String errorText(UiEvent event) {
        if (event.getPayload() instanceof UiEventPayloads.Error error) {
            return error.message();
        }
        return null;
    }

    private long safeSequence(UiEvent event) {
        return event.getSequence() == null ? 0 : event.getSequence();
    }

    private static final class TurnState {
        private final String turnId;
        private final int turn;
        private String commandId;
        private String status = RUNNING;
        private final Map<String, ItemState> items = new LinkedHashMap<>();

        private TurnState(String turnId, int turn) {
            this.turnId = turnId;
            this.turn = turn;
        }
    }

    private static final class ItemState {
        private final String id;
        private final UiItemKind kind;
        private final Integer contentIndex;
        private String status = RUNNING;
        private String text;
        private UiToolCall toolCall;
        private UiToolUpdate toolUpdate;
        private UiToolResult toolResult;

        private ItemState(String id, UiItemKind kind, Integer contentIndex) {
            this.id = id;
            this.kind = kind;
            this.contentIndex = contentIndex;
        }

        private UiHistoryItem toItem() {
                return new UiHistoryItem(id, kind, status, contentIndex, text, toolCall, toolUpdate, toolResult);
        }
    }
}
