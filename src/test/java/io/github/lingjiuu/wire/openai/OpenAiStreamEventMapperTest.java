package io.github.lingjiuu.wire.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputItemDoneEvent;
import com.openai.models.responses.ResponseStreamEvent;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.model.client.AssistantStreamEvent;
import junit.framework.TestCase;

import java.lang.reflect.Constructor;
import java.util.List;

public class OpenAiStreamEventMapperTest extends TestCase {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public void testToolBatchIdUsesResponseIdWhenPresent() throws Exception {
        OpenAiStreamEventMapper mapper = new OpenAiStreamEventMapper("fallback-model", "openai");
        mapper.map(createdEvent());

        AssistantStreamEvent start = mapper.map(ResponseStreamEvent.ofOutputItemAdded(ResponseOutputItemAddedEvent.builder()
                .item(toolCall("item-1", "call-1"))
                .outputIndex(0)
                .sequenceNumber(1)
                .build()));
        AssistantStreamEvent end = mapper.map(ResponseStreamEvent.ofOutputItemDone(ResponseOutputItemDoneEvent.builder()
                .item(toolCall("item-1", "call-1"))
                .outputIndex(0)
                .sequenceNumber(2)
                .build()));

        assertEquals("resp-1", start.getToolCall().getToolBatchId());
        assertEquals("resp-1", end.getToolCall().getToolBatchId());
    }

    public void testToolBatchIdFallsBackToOneLocalIdPerStream() {
        OpenAiStreamEventMapper mapper = new OpenAiStreamEventMapper("fallback-model", "openai");

        AssistantStreamEvent first = mapper.map(ResponseStreamEvent.ofOutputItemAdded(ResponseOutputItemAddedEvent.builder()
                .item(toolCall("item-1", "call-1"))
                .outputIndex(0)
                .sequenceNumber(1)
                .build()));
        AssistantStreamEvent second = mapper.map(ResponseStreamEvent.ofOutputItemAdded(ResponseOutputItemAddedEvent.builder()
                .item(toolCall("item-2", "call-2"))
                .outputIndex(1)
                .sequenceNumber(2)
                .build()));

        assertNotNull(first.getToolCall().getToolBatchId());
        assertFalse(first.getToolCall().getToolBatchId().isBlank());
        assertEquals(first.getToolCall().getToolBatchId(), second.getToolCall().getToolBatchId());
    }

    public void testRawJsonFallbackMapsTextDeltaAndMessageDone() throws Exception {
        OpenAiStreamEventMapper mapper = new OpenAiStreamEventMapper("fallback-model", "openai");

        AssistantStreamEvent created = mapper.map(rawOnlyEvent("""
                {
                  "type": "response.created",
                  "response": {
                    "id": "resp-raw",
                    "model": "gpt-raw",
                    "usage": null,
                    "error": null
                  }
                }
                """));
        AssistantStreamEvent start = mapper.map(rawOnlyEvent("""
                {
                  "type": "response.output_item.added",
                  "item": {"id": "msg-raw", "type": "message", "content": []},
                  "output_index": 0,
                  "sequence_number": 1
                }
                """));
        AssistantStreamEvent delta = mapper.map(rawOnlyEvent("""
                {
                  "type": "response.output_text.delta",
                  "item_id": "msg-raw",
                  "content_index": 0,
                  "output_index": 0,
                  "delta": "你好",
                  "sequence_number": 2
                }
                """));
        AssistantStreamEvent end = mapper.map(rawOnlyEvent("""
                {
                  "type": "response.output_item.done",
                  "item": {
                    "id": "msg-raw",
                    "type": "message",
                    "status": "completed",
                    "role": "assistant",
                    "content": [
                      {"type": "output_text", "text": "你好！", "annotations": [], "logprobs": []}
                    ]
                  },
                  "output_index": 0,
                  "sequence_number": 3
                }
                """));
        AssistantStreamEvent done = mapper.map(rawOnlyEvent("""
                {
                  "type": "response.completed",
                  "response": {
                    "id": "resp-raw",
                    "model": "gpt-raw",
                    "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
                    "error": null
                  }
                }
                """));

        assertEquals(AssistantStreamEvent.Type.START, created.getType());
        assertEquals(AssistantStreamEvent.Type.TEXT_START, start.getType());
        assertEquals(AssistantStreamEvent.Type.TEXT_DELTA, delta.getType());
        assertEquals("你好", delta.getDelta());
        assertEquals(AssistantStreamEvent.Type.TEXT_END, end.getType());
        assertEquals("你好！", end.getContent());
        assertNotNull(end.getProviderState());
        assertEquals(AssistantStreamEvent.Type.DONE, done.getType());
        assertEquals(AssistantMessage.StopReason.STOP, done.getMessage().getStopReason());
        assertEquals("你好！", ((TextContent) done.getMessage().messageContents().getFirst()).getText());
    }

    public void testCompletedWithoutVisibleOutputBecomesModelError() throws Exception {
        OpenAiStreamEventMapper mapper = new OpenAiStreamEventMapper("fallback-model", "openai");
        mapper.map(rawOnlyEvent("""
                {
                  "type": "response.created",
                  "response": {
                    "id": "resp-empty",
                    "model": "gpt-raw",
                    "usage": null,
                    "error": null
                  }
                }
                """));
        mapper.map(rawOnlyEvent("""
                {
                  "type": "response.output_item.added",
                  "item": {"id": "msg-empty", "type": "message", "content": []},
                  "output_index": 0,
                  "sequence_number": 1
                }
                """));

        AssistantStreamEvent done = mapper.map(rawOnlyEvent("""
                {
                  "type": "response.completed",
                  "response": {
                    "id": "resp-empty",
                    "model": "gpt-raw",
                    "usage": {},
                    "error": null
                  }
                }
                """));

        assertEquals(AssistantStreamEvent.Type.DONE, done.getType());
        assertEquals("error", done.getReason());
        assertEquals(AssistantMessage.StopReason.ERROR, done.getMessage().getStopReason());
        assertTrue(done.getMessage().getErrorMessage().contains("without visible assistant output"));
    }

    public void testRawOutputTextDoneCompletesTextItem() throws Exception {
        OpenAiStreamEventMapper mapper = new OpenAiStreamEventMapper("fallback-model", "openai");
        mapper.map(rawOnlyEvent("""
                {
                  "type": "response.created",
                  "response": {
                    "id": "resp-text-done",
                    "model": "gpt-raw",
                    "usage": null,
                    "error": null
                  }
                }
                """));
        mapper.map(rawOnlyEvent("""
                {
                  "type": "response.output_item.added",
                  "item": {"id": "msg-text-done", "type": "message", "content": []},
                  "output_index": 0,
                  "sequence_number": 1
                }
                """));

        AssistantStreamEvent end = mapper.map(rawOnlyEvent("""
                {
                  "type": "response.output_text.done",
                  "item_id": "msg-text-done",
                  "content_index": 0,
                  "output_index": 0,
                  "text": "done text",
                  "logprobs": [],
                  "sequence_number": 2
                }
                """));
        List<AssistantStreamEvent> completed = mapper.mapAll(rawOnlyEvent("""
                {
                  "type": "response.completed",
                  "response": {
                    "id": "resp-text-done",
                    "model": "gpt-raw",
                    "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
                    "error": null,
                    "output": [
                      {
                        "id": "msg-text-done",
                        "type": "message",
                        "status": "completed",
                        "role": "assistant",
                        "content": [
                          {"type": "output_text", "text": "done text", "annotations": [], "logprobs": []}
                        ]
                      }
                    ]
                  }
                }
                """));

        assertEquals(AssistantStreamEvent.Type.TEXT_END, end.getType());
        assertEquals("done text", end.getContent());
        assertNotNull(end.getProviderState());
        assertEquals(1, completed.size());
        assertEquals(AssistantStreamEvent.Type.DONE, completed.getFirst().getType());
        assertEquals("done text", ((TextContent) completed.getFirst().getMessage().messageContents().getFirst()).getText());
    }

    public void testTypedCompletedMayOmitOutputAfterTextDone() throws Exception {
        OpenAiStreamEventMapper mapper = new OpenAiStreamEventMapper("fallback-model", "openai");
        mapper.map(createdEvent());
        mapper.map(rawOnlyEvent("""
                {
                  "type": "response.output_item.added",
                  "item": {"id": "msg-no-output", "type": "message", "content": []},
                  "output_index": 0,
                  "sequence_number": 1
                }
                """));
        mapper.map(rawOnlyEvent("""
                {
                  "type": "response.output_text.done",
                  "item_id": "msg-no-output",
                  "content_index": 0,
                  "output_index": 0,
                  "text": "done text",
                  "logprobs": [],
                  "sequence_number": 2
                }
                """));

        List<AssistantStreamEvent> completed = mapper.mapAll(objectMapper.readValue("""
                {
                  "type": "response.completed",
                  "sequence_number": 3,
                  "response": {
                    "id": "resp-1",
                    "object": "response",
                    "created_at": 0,
                    "error": null,
                    "incomplete_details": null,
                    "instructions": null,
                    "metadata": {},
                    "model": "gpt-test",
                    "parallel_tool_calls": true,
                    "temperature": null,
                    "tool_choice": "auto",
                    "tools": [],
                    "top_p": null,
                    "background": null,
                    "completed_at": 0,
                    "conversation": null,
                    "max_output_tokens": null,
                    "max_tool_calls": null,
                    "previous_response_id": null,
                    "prompt": null,
                    "prompt_cache_key": null,
                    "prompt_cache_retention": null,
                    "reasoning": null,
                    "safety_identifier": null,
                    "service_tier": null,
                    "status": "completed",
                    "text": null,
                    "top_logprobs": null,
                    "truncation": "disabled",
                    "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
                    "user": null
                  }
                }
                """, ResponseStreamEvent.class));

        assertEquals(1, completed.size());
        assertEquals(AssistantStreamEvent.Type.DONE, completed.getFirst().getType());
        assertEquals("done text", ((TextContent) completed.getFirst().getMessage().messageContents().getFirst()).getText());
    }

    public void testRawCompletedOutputSynthesizesTextItemBeforeDone() throws Exception {
        OpenAiStreamEventMapper mapper = new OpenAiStreamEventMapper("fallback-model", "openai");
        mapper.map(rawOnlyEvent("""
                {
                  "type": "response.created",
                  "response": {
                    "id": "resp-final-output",
                    "model": "gpt-raw",
                    "usage": null,
                    "error": null
                  }
                }
                """));
        mapper.map(rawOnlyEvent("""
                {
                  "type": "response.output_item.added",
                  "item": {"id": "rsn-final-output", "type": "reasoning", "summary": []},
                  "output_index": 0,
                  "sequence_number": 1
                }
                """));
        mapper.map(rawOnlyEvent("""
                {
                  "type": "response.output_item.done",
                  "item": {"id": "rsn-final-output", "type": "reasoning", "summary": []},
                  "output_index": 0,
                  "sequence_number": 2
                }
                """));

        List<AssistantStreamEvent> events = mapper.mapAll(rawOnlyEvent("""
                {
                  "type": "response.completed",
                  "response": {
                    "id": "resp-final-output",
                    "model": "gpt-raw",
                    "usage": {"input_tokens": 1, "output_tokens": 2, "total_tokens": 3},
                    "error": null,
                    "output": [
                      {"id": "rsn-final-output", "type": "reasoning", "summary": []},
                      {
                        "id": "msg-final-output",
                        "type": "message",
                        "status": "completed",
                        "role": "assistant",
                        "content": [
                          {"type": "output_text", "text": "final recovered text", "annotations": [], "logprobs": []}
                        ]
                      }
                    ]
                  }
                }
                """));

        assertEquals(2, events.size());
        assertEquals(AssistantStreamEvent.Type.TEXT_END, events.get(0).getType());
        assertEquals("msg-final-output", events.get(0).getItemId());
        assertEquals("final recovered text", events.get(0).getContent());
        assertEquals(AssistantStreamEvent.Type.DONE, events.get(1).getType());
        assertEquals(AssistantMessage.StopReason.STOP, events.get(1).getMessage().getStopReason());
        assertEquals("final recovered text", ((TextContent) events.get(1).getMessage().messageContents().get(1)).getText());
    }

    private ResponseFunctionToolCall toolCall(String itemId, String callId) {
        return ResponseFunctionToolCall.builder()
                .id(itemId)
                .callId(callId)
                .name("bash")
                .arguments("{}")
                .build();
    }

    private ResponseStreamEvent createdEvent() throws Exception {
        return objectMapper.readValue("""
                {
                  "type": "response.created",
                  "sequence_number": 0,
                  "response": {
                    "id": "resp-1",
                    "object": "response",
                    "created_at": 0,
                    "error": null,
                    "incomplete_details": null,
                    "instructions": null,
                    "metadata": {},
                    "model": "gpt-test",
                    "output": [],
                    "parallel_tool_calls": true,
                    "temperature": null,
                    "tool_choice": "auto",
                    "tools": [],
                    "top_p": null,
                    "background": null,
                    "completed_at": null,
                    "conversation": null,
                    "max_output_tokens": null,
                    "max_tool_calls": null,
                    "previous_response_id": null,
                    "prompt": null,
                    "prompt_cache_key": null,
                    "prompt_cache_retention": null,
                    "reasoning": null,
                    "safety_identifier": null,
                    "service_tier": null,
                    "status": "in_progress",
                    "text": null,
                    "top_logprobs": null,
                    "truncation": "disabled",
                    "usage": null,
                    "user": null
                  }
                }
                """, ResponseStreamEvent.class);
    }

    private ResponseStreamEvent rawOnlyEvent(String json) throws Exception {
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : ResponseStreamEvent.class.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = candidate.getParameterTypes();
            if (parameterTypes.length > 0 && parameterTypes[parameterTypes.length - 1] == JsonValue.class) {
                constructor = candidate;
                break;
            }
        }
        assertNotNull(constructor);
        constructor.setAccessible(true);
        Object[] args = new Object[constructor.getParameterCount()];
        args[args.length - 1] = JsonValue.fromJsonNode(objectMapper.readTree(json));
        return (ResponseStreamEvent) constructor.newInstance(args);
    }
}
