package io.github.lingjiuu.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import junit.framework.TestCase;

import java.util.List;

public class ProtocolJsonTest extends TestCase {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public void testSubmitCommandWireFormat() throws Exception {
        UiCommand command = UiCommand.builder()
                .commandId("cmd-1")
                .type(UiCommandType.SUBMIT_USER_INPUT)
                .payload(new UiCommandPayloads.SubmitUserInput("hello"))
                .build();

        assertJsonEquals("""
                {
                  "commandId": "cmd-1",
                  "type": "SUBMIT_USER_INPUT",
                  "payload": {
                    "payloadType": "submitUserInput",
                    "text": "hello"
                  }
                }
                """, command);

        UiCommand decoded = objectMapper.readValue(objectMapper.writeValueAsString(command), UiCommand.class);
        assertEquals("cmd-1", decoded.getCommandId());
        assertEquals(UiCommandType.SUBMIT_USER_INPUT, decoded.getType());
        assertTrue(decoded.getPayload() instanceof UiCommandPayloads.SubmitUserInput);
    }

    public void testTurnStartedEventWireFormat() throws Exception {
        UiEvent event = UiEvent.builder()
                .type(UiEventType.TURN_STARTED)
                .sequence(7L)
                .timestampMs(1000L)
                .sessionId("session-1")
                .commandId("cmd-1")
                .turnId("turn-1")
                .turn(1)
                .build();

        assertJsonEquals("""
                {
                  "type": "TURN_STARTED",
                  "sequence": 7,
                  "timestampMs": 1000,
                  "sessionId": "session-1",
                  "commandId": "cmd-1",
                  "turnId": "turn-1",
                  "turn": 1,
                  "payload": null
                }
                """, event);
    }

    public void testAssistantItemCompletedWireFormat() throws Exception {
        UiEvent event = UiEvent.builder()
                .type(UiEventType.ITEM_COMPLETED)
                .sequence(8L)
                .timestampMs(1001L)
                .sessionId("session-1")
                .commandId("cmd-1")
                .turnId("turn-1")
                .turn(1)
                .payload(new UiEventPayloads.ItemCompleted(UiItem.builder()
                        .itemId("msg-1")
                        .kind(UiItemKind.ASSISTANT_TEXT)
                        .contentIndex(0)
                        .body(new UiItemBodies.Text("hello"))
                        .build()))
                .build();

        assertJsonEquals("""
                {
                  "type": "ITEM_COMPLETED",
                  "sequence": 8,
                  "timestampMs": 1001,
                  "sessionId": "session-1",
                  "commandId": "cmd-1",
                  "turnId": "turn-1",
                  "turn": 1,
                  "payload": {
                    "payloadType": "itemCompleted",
                    "item": {
                      "itemId": "msg-1",
                      "kind": "ASSISTANT_TEXT",
                      "contentIndex": 0,
                      "body": {
                        "bodyType": "text",
                        "text": "hello"
                      }
                    }
                  }
                }
                """, event);
    }

    public void testToolCallItemCompletedWireFormat() throws Exception {
        UiToolCall toolCall = UiToolCall.builder()
                .itemId("tool-1")
                .contentIndex(1)
                .toolCallId("call-1")
                .toolName("ls")
                .argumentsJson("{\"path\":\".\"}")
                .build();
        UiEvent event = UiEvent.builder()
                .type(UiEventType.ITEM_COMPLETED)
                .sequence(9L)
                .timestampMs(1002L)
                .sessionId("session-1")
                .commandId("cmd-1")
                .turnId("turn-1")
                .turn(1)
                .payload(new UiEventPayloads.ItemCompleted(UiItem.builder()
                        .itemId("tool-1")
                        .kind(UiItemKind.TOOL_CALL)
                        .contentIndex(1)
                        .body(new UiItemBodies.ToolCall(toolCall))
                        .build()))
                .build();

        assertJsonEquals("""
                {
                  "type": "ITEM_COMPLETED",
                  "sequence": 9,
                  "timestampMs": 1002,
                  "sessionId": "session-1",
                  "commandId": "cmd-1",
                  "turnId": "turn-1",
                  "turn": 1,
                  "payload": {
                    "payloadType": "itemCompleted",
                    "item": {
                      "itemId": "tool-1",
                      "kind": "TOOL_CALL",
                      "contentIndex": 1,
                      "body": {
                        "bodyType": "toolCall",
                        "toolCall": {
                          "itemId": "tool-1",
                          "contentIndex": 1,
                          "toolCallId": "call-1",
                          "toolName": "ls",
                          "argumentsJson": "{\\\"path\\\":\\\".\\\"}"
                        }
                      }
                    }
                  }
                }
                """, event);
    }

    public void testToolArgumentsDeltaWireFormat() throws Exception {
        UiEvent event = UiEvent.builder()
                .type(UiEventType.TOOL_CALL_ARGUMENTS_DELTA)
                .sequence(8L)
                .timestampMs(1001L)
                .sessionId("session-1")
                .commandId("cmd-1")
                .turnId("turn-1")
                .turn(1)
                .payload(new UiEventPayloads.ToolArgumentsDelta(
                        "item-tool",
                        0,
                        UiToolCall.builder()
                                .itemId("item-tool")
                                .contentIndex(0)
                                .toolCallId("call-1")
                                .toolName("ls")
                                .argumentsJson("{\"path\"")
                                .build(),
                        "{\"path\""
                ))
                .build();

        assertJsonEquals("""
                {
                  "type": "TOOL_CALL_ARGUMENTS_DELTA",
                  "sequence": 8,
                  "timestampMs": 1001,
                  "sessionId": "session-1",
                  "commandId": "cmd-1",
                  "turnId": "turn-1",
                  "turn": 1,
                  "payload": {
                    "payloadType": "toolArgumentsDelta",
                    "itemId": "item-tool",
                    "contentIndex": 0,
                    "toolCall": {
                      "itemId": "item-tool",
                      "contentIndex": 0,
                      "toolCallId": "call-1",
                      "toolName": "ls",
                      "argumentsJson": "{\\\"path\\\""
                    },
                    "delta": "{\\\"path\\\""
                  }
                }
                """, event);
    }

    public void testToolResultEventWireFormat() throws Exception {
        UiToolResult toolResult = UiToolResult.builder()
                .itemId("result-1")
                .sourceItemId("item-tool")
                .contentIndex(0)
                .toolCallId("call-1")
                .toolName("ls")
                .text("done")
                .error(false)
                .status("COMPLETED")
                .durationMs(12L)
                .truncated(false)
                .build();
        UiEvent event = UiEvent.builder()
                .type(UiEventType.TOOL_RESULT)
                .sequence(9L)
                .timestampMs(1002L)
                .sessionId("session-1")
                .commandId("cmd-1")
                .turnId("turn-1")
                .turn(1)
                .payload(new UiEventPayloads.ToolResult(UiItem.builder()
                        .itemId("result-1")
                        .kind(UiItemKind.TOOL_RESULT)
                        .contentIndex(0)
                        .body(new UiItemBodies.ToolResult(toolResult))
                        .build()))
                .build();

        assertJsonEquals("""
                {
                  "type": "TOOL_RESULT",
                  "sequence": 9,
                  "timestampMs": 1002,
                  "sessionId": "session-1",
                  "commandId": "cmd-1",
                  "turnId": "turn-1",
                  "turn": 1,
                  "payload": {
                    "payloadType": "toolResult",
                    "item": {
                      "itemId": "result-1",
                      "kind": "TOOL_RESULT",
                      "contentIndex": 0,
                      "body": {
                        "bodyType": "toolResult",
                        "toolResult": {
                          "itemId": "result-1",
                          "sourceItemId": "item-tool",
                          "contentIndex": 0,
                          "toolCallId": "call-1",
                          "toolName": "ls",
                          "text": "done",
                          "error": false,
                          "status": "COMPLETED",
                          "durationMs": 12,
                          "details": null,
                          "truncated": false
                        }
                      }
                    }
                  }
                }
                """, event);
    }

    public void testHistoryWireFormat() throws Exception {
        UiToolCall toolCall = UiToolCall.builder()
                .itemId("item-tool")
                .contentIndex(0)
                .toolCallId("call-1")
                .toolName("ls")
                .argumentsJson("{}")
                .build();
        UiToolResult toolResult = UiToolResult.builder()
                .itemId("result-1")
                .sourceItemId("item-tool")
                .contentIndex(0)
                .toolCallId("call-1")
                .toolName("ls")
                .text("done")
                .error(false)
                .status("COMPLETED")
                .durationMs(12L)
                .build();
        UiHistory history = new UiHistory("session-1", List.of(new UiTurn(
                "turn-1",
                "cmd-1",
                1,
                "COMPLETED",
                List.of(new UiHistoryItem(
                        "item-tool",
                        UiItemKind.TOOL_CALL,
                        "COMPLETED",
                        0,
                        null,
                        toolCall,
                        toolResult
                ))
        )));

        assertJsonEquals("""
                {
                  "sessionId": "session-1",
                  "turns": [
                    {
                      "turnId": "turn-1",
                      "commandId": "cmd-1",
                      "turn": 1,
                      "status": "COMPLETED",
                      "items": [
                        {
                          "id": "item-tool",
                          "kind": "TOOL_CALL",
                          "status": "COMPLETED",
                          "contentIndex": 0,
                          "text": null,
                          "toolCall": {
                            "itemId": "item-tool",
                            "contentIndex": 0,
                            "toolCallId": "call-1",
                            "toolName": "ls",
                            "argumentsJson": "{}"
                          },
                          "toolResult": {
                            "itemId": "result-1",
                            "sourceItemId": "item-tool",
                            "contentIndex": 0,
                            "toolCallId": "call-1",
                            "toolName": "ls",
                            "text": "done",
                            "error": false,
                            "status": "COMPLETED",
                            "durationMs": 12,
                            "details": null,
                            "truncated": null
                          }
                        }
                      ]
                    }
                  ]
                }
                """, history);
    }

    public void testEventPageWireFormat() throws Exception {
        UiEventPage page = new UiEventPage(
                "session-1",
                7,
                List.of(UiEvent.builder()
                        .type(UiEventType.TURN_COMPLETED)
                        .sequence(9L)
                        .timestampMs(1002L)
                        .sessionId("session-1")
                        .turnId("turn-1")
                        .turn(1)
                        .build()),
                9,
                true,
                false
        );

        assertJsonEquals("""
                {
                  "sessionId": "session-1",
                  "afterSequence": 7,
                  "events": [
                    {
                      "type": "TURN_COMPLETED",
                      "sequence": 9,
                      "timestampMs": 1002,
                      "sessionId": "session-1",
                      "commandId": null,
                      "turnId": "turn-1",
                      "turn": 1,
                      "payload": null
                    }
                  ],
                  "nextAfterSequence": 9,
                  "hasMore": true,
                  "replayRequired": false
                }
                """, page);
    }

    private void assertJsonEquals(String expectedJson, Object value) throws Exception {
        JsonNode expected = objectMapper.readTree(expectedJson);
        JsonNode actual = objectMapper.readTree(objectMapper.writeValueAsString(value));
        assertEquals(expected, actual);
    }
}
