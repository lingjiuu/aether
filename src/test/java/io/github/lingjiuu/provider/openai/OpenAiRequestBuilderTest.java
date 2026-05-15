package io.github.lingjiuu.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionCallOutputItem;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import io.github.lingjiuu.llm.LlmCallOptions;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import junit.framework.TestCase;

import java.util.List;
import java.util.Map;

public class OpenAiRequestBuilderTest extends TestCase {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public void testProjectorPrefersReplayDataWhenPresent() throws Exception {
        OpenAiRequestBuilder requestBuilder = new OpenAiRequestBuilder();

        OpenAiReplayData replayData = OpenAiReplayData.builder()
                .responseId("resp_123")
                .items(List.of(
                        OpenAiReplayData.ReplayItem.builder()
                                .type(OpenAiReplayData.Type.OUTPUT_MESSAGE)
                                .json(objectMapper.writeValueAsString(ResponseOutputMessage.builder()
                                        .id("msg_replay")
                                        .role(JsonValue.from("assistant"))
                                        .status(ResponseOutputMessage.Status.COMPLETED)
                                        .addContent(ResponseOutputText.builder()
                                                .text("replayed answer")
                                                .annotations(List.of())
                                                .build())
                                        .build()))
                                .build(),
                        OpenAiReplayData.ReplayItem.builder()
                                .type(OpenAiReplayData.Type.FUNCTION_CALL)
                                .json(objectMapper.writeValueAsString(ResponseFunctionToolCall.builder()
                                        .callId("call-replay")
                                        .name("sample_tool")
                                        .arguments("{\"value\":\"UTC\"}")
                                        .build()))
                                .build()
                ))
                .build();

        AssistantMessage assistantMessage = AssistantMessage.builder()
                .provider("openai")
                .model("gpt-4.1")
                .stopReason(AssistantMessage.StopReason.TOOLUSE)
                .providerState(replayData)
                .contents(List.of(
                        TextContent.builder().text("fallback answer").build(),
                        ToolCallContent.builder()
                                .toolCallId("call-fallback")
                                .toolName("fallback_tool")
                                .argumentsJson("{\"unused\":true}")
                                .build()
                ))
                .build();

        ResponseCreateParams params = requestBuilder.buildRequest(LlmRequest.builder()
                .systemPrompt("You are helpful")
                .model(LlmModel.builder()
                        .id("gpt-4.1")
                        .provider("openai")
                        .api("openai")
                        .baseUrl("https://api.openai.com/v1")
                        .build())
                .messages(List.of(
                        UserMessage.builder()
                                .contents(List.of(TextContent.builder().text("What time is it?").build()))
                                .build(),
                        assistantMessage,
                        ToolResultMessage.builder()
                                .toolCallId("call-replay")
                                .toolName("sample_tool")
                                .contents(List.of(TextContent.builder().text("{\"value\":\"12:00\"}").build()))
                                .build()
                ))
                .callOptions(LlmCallOptions.builder().build())
                .build());

        assertTrue(params.input().isPresent());
        List<ResponseInputItem> inputItems = params.input().get().asResponse();

        assertEquals(5, inputItems.size());

        ResponseInputItem developerMessage = inputItems.get(0);
        assertTrue(developerMessage.isEasyInputMessage());
        assertEquals(EasyInputMessage.Role.DEVELOPER, developerMessage.asEasyInputMessage().role());
        assertEquals("You are helpful", developerMessage.asEasyInputMessage().content().asTextInput());

        ResponseInputItem userMessage = inputItems.get(1);
        assertTrue(userMessage.isEasyInputMessage());
        assertEquals(EasyInputMessage.Role.USER, userMessage.asEasyInputMessage().role());
        assertEquals("What time is it?", userMessage.asEasyInputMessage().content().asTextInput());

        ResponseInputItem replayedOutputMessage = inputItems.get(2);
        assertTrue(replayedOutputMessage.isResponseOutputMessage());
        assertEquals(
                "replayed answer",
                replayedOutputMessage.asResponseOutputMessage().content().getFirst().asOutputText().text()
        );

        ResponseInputItem replayedFunctionCall = inputItems.get(3);
        assertTrue(replayedFunctionCall.isFunctionCall());
        assertEquals("call-replay", replayedFunctionCall.asFunctionCall().callId());
        assertEquals("sample_tool", replayedFunctionCall.asFunctionCall().name());

        ResponseInputItem functionCallOutput = inputItems.get(4);
        assertTrue(functionCallOutput.isFunctionCallOutput());
        assertEquals("call-replay", functionCallOutput.asFunctionCallOutput().callId());
        assertEquals("{\"value\":\"12:00\"}", functionCallOutput.asFunctionCallOutput().output().asString());

        for (ResponseInputItem item : inputItems) {
            assertFalse(item.toString().contains("fallback answer"));
            assertFalse(item.toString().contains("call-fallback"));
        }
    }

    public void testProjectorFallsBackToGenericContentsWithoutReplayData() throws Exception {
        OpenAiRequestBuilder requestBuilder = new OpenAiRequestBuilder();

        AssistantMessage assistantMessage = AssistantMessage.builder()
                .provider("openai")
                .model("gpt-4.1")
                .stopReason(AssistantMessage.StopReason.TOOLUSE)
                .contents(List.of(
                        TextContent.builder().text("fallback answer").build(),
                        ToolCallContent.builder()
                                .toolCallId("call-fallback")
                                .toolName("sample_tool")
                                .argumentsJson("{\"value\":\"UTC\"}")
                                .build()
                ))
                .build();

        ResponseCreateParams params = requestBuilder.buildRequest(LlmRequest.builder()
                .model(LlmModel.builder()
                        .id("gpt-4.1")
                        .provider("openai")
                        .api("openai")
                        .baseUrl("https://api.openai.com/v1")
                        .build())
                .messages(List.of(
                        assistantMessage,
                        ToolResultMessage.builder()
                                .toolCallId("call-fallback")
                                .toolName("sample_tool")
                                .contents(List.of(TextContent.builder().text("{\"value\":\"12:00\"}").build()))
                                .build()
                ))
                .callOptions(LlmCallOptions.builder().build())
                .build());

        assertTrue(params.input().isPresent());
        List<ResponseInputItem> inputItems = params.input().get().asResponse();

        assertEquals(3, inputItems.size());

        ResponseInputItem outputMessage = inputItems.get(0);
        assertTrue(outputMessage.isResponseOutputMessage());
        assertEquals("fallback answer", outputMessage.asResponseOutputMessage().content().getFirst().asOutputText().text());

        ResponseInputItem functionCall = inputItems.get(1);
        assertTrue(functionCall.isFunctionCall());
        assertEquals("call-fallback", functionCall.asFunctionCall().callId());
        assertEquals("sample_tool", functionCall.asFunctionCall().name());
        assertEquals("{\"value\":\"UTC\"}", functionCall.asFunctionCall().arguments());

        ResponseInputItem functionCallOutput = inputItems.get(2);
        assertTrue(functionCallOutput.isFunctionCallOutput());
        assertEquals("call-fallback", functionCallOutput.asFunctionCallOutput().callId());
        assertEquals("{\"value\":\"12:00\"}", functionCallOutput.asFunctionCallOutput().output().asString());
    }

    public void testSerializesUserAndToolResultImages() throws Exception {
        OpenAiRequestBuilder requestBuilder = new OpenAiRequestBuilder();

        ResponseCreateParams params = requestBuilder.buildRequest(LlmRequest.builder()
                .model(LlmModel.builder()
                        .id("gpt-4.1")
                        .provider("openai")
                        .api("openai")
                        .baseUrl("https://api.openai.com/v1")
                        .build())
                .messages(List.of(
                        UserMessage.builder()
                                .contents(List.of(
                                        TextContent.builder().text("look").build(),
                                        ImageContent.builder()
                                                .data("abc123")
                                                .mimeType("image/png")
                                                .build()
                                ))
                                .build(),
                        AssistantMessage.builder()
                                .provider("openai")
                                .model("gpt-4.1")
                                .stopReason(AssistantMessage.StopReason.TOOLUSE)
                                .contents(List.of(ToolCallContent.builder()
                                        .toolCallId("call-1")
                                        .toolName("read")
                                        .argumentsJson("{\"path\":\"pixel.png\"}")
                                        .build()))
                                .build(),
                        ToolResultMessage.builder()
                                .toolCallId("call-1")
                                .toolName("read")
                                .contents(List.of(
                                        TextContent.builder().text("Read image file [image/png]").build(),
                                        ImageContent.builder()
                                                .data("def456")
                                                .mimeType("image/png")
                                                .build()
                                ))
                                .build()
                ))
                .callOptions(LlmCallOptions.builder().build())
                .build());

        List<ResponseInputItem> inputItems = params.input().orElseThrow().asResponse();
        assertEquals(3, inputItems.size());

        ResponseInputItem userMessage = inputItems.get(0);
        assertTrue(userMessage.isEasyInputMessage());
        assertTrue(userMessage.asEasyInputMessage().content().isResponseInputMessageContentList());
        assertEquals(2, userMessage.asEasyInputMessage().content().asResponseInputMessageContentList().size());
        assertTrue(userMessage.asEasyInputMessage().content().asResponseInputMessageContentList().get(1).isInputImage());
        assertEquals("data:image/png;base64,abc123",
                userMessage.asEasyInputMessage().content().asResponseInputMessageContentList().get(1).asInputImage().imageUrl().orElseThrow());

        ResponseInputItem functionCall = inputItems.get(1);
        assertTrue(functionCall.isFunctionCall());
        assertEquals("call-1", functionCall.asFunctionCall().callId());

        ResponseInputItem functionCallOutput = inputItems.get(2);
        assertTrue(functionCallOutput.isFunctionCallOutput());
        List<ResponseFunctionCallOutputItem> outputItems = functionCallOutput.asFunctionCallOutput()
                .output()
                .asResponseFunctionCallOutputItemList();
        assertEquals(2, outputItems.size());
        assertTrue(outputItems.get(0).isInputText());
        assertTrue(outputItems.get(1).isInputImage());
        assertEquals("Read image file [image/png]", outputItems.get(0).asInputText().text());
        assertEquals("data:image/png;base64,def456",
                outputItems.get(1).asInputImage().imageUrl().orElseThrow());
    }

    public void testBuildRequestSerializesProviderNeutralTools() {
        OpenAiRequestBuilder requestBuilder = new OpenAiRequestBuilder();

        ResponseCreateParams params = requestBuilder.buildRequest(LlmRequest.builder()
                .model(LlmModel.builder()
                        .id("gpt-4.1")
                        .provider("openai")
                        .api("openai")
                        .baseUrl("https://api.openai.com/v1")
                        .build())
                .tools(List.of(new SampleTool()))
                .callOptions(LlmCallOptions.builder().build())
                .build());

        assertTrue(params.tools().isPresent());
        assertEquals(1, params.tools().get().size());
        assertTrue(params.tools().get().getFirst().isFunction());
        assertEquals("sample_tool", params.tools().get().getFirst().asFunction().name());
        assertEquals("Sample tool for provider serialization.", params.tools().get().getFirst().asFunction().description().orElse(null));
        assertEquals(Boolean.TRUE, params.tools().get().getFirst().asFunction().strict().orElse(null));
        assertEquals(
                Map.of(
                        "type", JsonValue.from("object"),
                        "properties", JsonValue.from(Map.of("value", Map.of("type", "string"))),
                        "required", JsonValue.from(List.of("value"))
                ),
                params.tools().get().getFirst().asFunction().parameters().orElseThrow()._additionalProperties()
        );
    }

    private static final class SampleTool implements ToolDefinition {

        @Override
        public String name() {
            return "sample_tool";
        }

        @Override
        public String label() {
            return "sample_tool";
        }

        @Override
        public String description() {
            return "Sample tool for provider serialization.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of("value", Map.of("type", "string")),
                    "required", List.of("value")
            );
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.text("ok");
        }
    }
}
