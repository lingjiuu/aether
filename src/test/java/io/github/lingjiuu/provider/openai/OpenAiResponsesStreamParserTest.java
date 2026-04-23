package io.github.lingjiuu.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseStreamEvent;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class OpenAiResponsesStreamParserTest extends TestCase {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public void testParserBuildsFinalAssistantMessageAndReplayData() throws Exception {
        List<ResponseStreamEvent> events = List.of(
                parseEvent("""
                        {
                          "type": "response.created",
                          "response": {
                            "id": "resp_123",
                            "object": "response",
                            "created_at": 1740855869,
                            "status": "in_progress",
                            "error": null,
                            "incomplete_details": null,
                            "model": "gpt-4.1",
                            "output": [],
                            "tool_choice": "auto",
                            "tools": [],
                            "metadata": {}
                          }
                        }
                        """),
                parseEvent("""
                        {
                          "type": "response.output_item.added",
                          "output_index": 0,
                          "item": {
                            "id": "msg_123",
                            "type": "message",
                            "status": "in_progress",
                            "role": "assistant",
                            "content": []
                          }
                        }
                        """),
                parseEvent("""
                        {
                          "type": "response.output_text.delta",
                          "item_id": "msg_123",
                          "output_index": 0,
                          "content_index": 0,
                          "delta": "Done.",
                          "sequence_number": 1
                        }
                        """),
                parseEvent("""
                        {
                          "type": "response.output_item.done",
                          "output_index": 0,
                          "item": {
                            "id": "msg_123",
                            "type": "message",
                            "status": "completed",
                            "role": "assistant",
                            "content": [
                              {
                                "type": "output_text",
                                "text": "Done.",
                                "annotations": []
                              }
                            ]
                          }
                        }
                        """),
                parseEvent("""
                        {
                          "type": "response.completed",
                          "response": {
                            "id": "resp_123",
                            "object": "response",
                            "created_at": 1740855869,
                            "status": "completed",
                            "completed_at": 1740855870,
                            "error": null,
                            "incomplete_details": null,
                            "input": [],
                            "instructions": null,
                            "max_output_tokens": null,
                            "model": "gpt-4.1",
                            "output": [
                              {
                                "id": "msg_123",
                                "type": "message",
                                "status": "completed",
                                "role": "assistant",
                                "content": [
                                  {
                                    "type": "output_text",
                                    "text": "Done.",
                                    "annotations": []
                                  }
                                ]
                              }
                            ],
                            "previous_response_id": null,
                            "reasoning_effort": null,
                            "store": false,
                            "temperature": 1,
                            "text": {
                              "format": {
                                "type": "text"
                              }
                            },
                            "tool_choice": "auto",
                            "tools": [],
                            "top_p": 1,
                            "truncation": "disabled",
                            "usage": {
                              "input_tokens": 1,
                              "output_tokens": 1,
                              "output_tokens_details": {
                                "reasoning_tokens": 0
                              },
                              "total_tokens": 2
                            },
                            "user": null,
                            "metadata": {}
                          },
                          "sequence_number": 2
                        }
                        """)
        );

        OpenAiResponsesStreamParser parser = new OpenAiResponsesStreamParser();
        AssistantStream stream = parser.parseStream(new FakeStreamResponse(events), "gpt-4.1", "openai");

        List<AssistantStreamEvent.Type> eventTypes = new ArrayList<>();
        AssistantMessage result = stream.consume(event -> eventTypes.add(event.getType()));

        assertEquals(List.of(
                AssistantStreamEvent.Type.START,
                AssistantStreamEvent.Type.TEXT_START,
                AssistantStreamEvent.Type.TEXT_DELTA,
                AssistantStreamEvent.Type.TEXT_END,
                AssistantStreamEvent.Type.DONE
        ), eventTypes);
        assertEquals("Done.", MessageContents.text(result));
        assertEquals(AssistantMessage.StopReason.STOP, result.getStopReason());
        assertTrue(result.getProviderState() instanceof OpenAiReplayData);
        OpenAiReplayData replayData = (OpenAiReplayData) result.getProviderState();
        assertEquals("resp_123", replayData.getResponseId());
        assertEquals(1, replayData.getItems().size());
        assertEquals(OpenAiReplayData.Type.OUTPUT_MESSAGE, replayData.getItems().getFirst().getType());
    }

    private ResponseStreamEvent parseEvent(String json) throws Exception {
        return objectMapper.readValue(json, ResponseStreamEvent.class);
    }

    private static final class FakeStreamResponse implements StreamResponse<ResponseStreamEvent> {
        private final List<ResponseStreamEvent> events;

        private FakeStreamResponse(List<ResponseStreamEvent> events) {
            this.events = events;
        }

        @Override
        public Stream<ResponseStreamEvent> stream() {
            return events.stream();
        }

        @Override
        public void close() {
        }
    }
}
