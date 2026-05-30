package io.github.lingjiuu.wire.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputItemDoneEvent;
import com.openai.models.responses.ResponseStreamEvent;
import io.github.lingjiuu.model.client.AssistantStreamEvent;
import junit.framework.TestCase;

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
}
