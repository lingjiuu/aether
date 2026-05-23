package io.github.lingjiuu.ui.history;

import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.agent.turn.TurnId;
import io.github.lingjiuu.event.EventManager;
import io.github.lingjiuu.event.UiEvents;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventPayloads;
import io.github.lingjiuu.protocol.UiHistory;
import io.github.lingjiuu.protocol.UiItemBodies;
import io.github.lingjiuu.protocol.UiItemKind;
import io.github.lingjiuu.tool.ToolExecutionResult;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class UiHistoryStateTest extends TestCase {

    public void testBuildsRenderableHistoryFromEvents() {
        EventManager events = new EventManager();
        List<UiEvent> captured = new CopyOnWriteArrayList<>();
        TurnContext turnContext = new TurnContext(TurnId.create(), "session-1", 1, Path.of("."), "cmd-1");
        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName("ls")
                .argumentsJson("{}")
                .build();
        try {
            events.subscribe(captured::add);
            events.emit(UiEvents.turnStarted(turnContext));
            events.emit(UiEvents.itemStarted(turnContext, UiItemKind.ASSISTANT_TEXT, "msg-1", 0, null));
            events.emit(UiEvents.assistantTextDelta(turnContext, "msg-1", 0, "hel"));
            events.emit(UiEvents.assistantTextDelta(turnContext, "msg-1", 0, "lo"));
            events.emit(UiEvents.itemCompleted(turnContext, UiItemKind.ASSISTANT_TEXT, "msg-1", 0, null, "hello"));
            events.emit(UiEvents.toolCall("tool-item-1", 1, toolCall, turnContext));
            events.emit(UiEvents.toolExecutionUpdate(
                    "tool-item-1",
                    1,
                    toolCall,
                    ToolExecutionResult.text("working"),
                    turnContext
            ));
            io.github.lingjiuu.message.ToolResultMessage toolResult =
                    io.github.lingjiuu.message.ToolResultMessage.builder()
                            .id("tool-result-1")
                            .toolCallId("call-1")
                            .toolName("ls")
                            .contents(List.of(io.github.lingjiuu.message.content.TextContent.builder()
                                    .text("done")
                                    .build()))
                            .build();
            events.emit(UiEvents.toolExecutionEnd(
                    "tool-item-1",
                    1,
                    toolCall,
                    toolResult,
                    "COMPLETED",
                    12L,
                    turnContext
            ));
            events.emit(UiEvents.toolResult("tool-item-1", 1, toolCall, toolResult, "COMPLETED", 12L, turnContext));
            events.emit(UiEvents.turnCompleted(turnContext));
            events.flush();

            UiHistory history = UiHistoryState.fromEvents("session-1", captured);

            assertEquals("session-1", history.sessionId());
            assertEquals(1, history.turns().size());
            assertEquals(turnContext.turnId().value(), history.turns().getFirst().turnId());
            assertEquals("cmd-1", history.turns().getFirst().commandId());
            assertEquals("COMPLETED", history.turns().getFirst().status());
            assertEquals(2, history.turns().getFirst().items().size());
            assertEquals("hello", history.turns().getFirst().items().getFirst().text());
            assertEquals("COMPLETED", history.turns().getFirst().items().get(1).status());
            assertEquals("done", history.turns().getFirst().items().get(1).toolResult().getText());
            assertTrue(captured.stream().allMatch(event -> turnContext.turnId().value().equals(event.getTurnId())));
            assertTrue(captured.stream().allMatch(event -> "cmd-1".equals(event.getCommandId())));
        } finally {
            events.close();
        }
    }

    public void testToolResultItemCompletedCarriesToolResult() {
        io.github.lingjiuu.protocol.UiToolResult result = io.github.lingjiuu.protocol.UiToolResult.builder()
                .itemId("result-1")
                .sourceItemId("tool-1")
                .toolCallId("call-1")
                .toolName("ls")
                .text("ok")
                .status("COMPLETED")
                .build();
        UiEvent event = UiEvent.builder()
                .type(io.github.lingjiuu.protocol.UiEventType.TOOL_RESULT)
                .sessionId("session-1")
                .turnId("turn-1")
                .turn(1)
                .payload(new UiEventPayloads.ToolResult(io.github.lingjiuu.protocol.UiItem.builder()
                        .itemId("result-1")
                        .kind(UiItemKind.TOOL_RESULT)
                        .body(new UiItemBodies.ToolResult(result))
                        .build()))
                .build();

        UiHistory history = UiHistoryState.fromEvents("session-1", List.of(event));

        assertEquals(1, history.turns().getFirst().items().size());
        assertEquals("ok", history.turns().getFirst().items().getFirst().toolResult().getText());
    }
}
