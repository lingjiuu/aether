package io.github.lingjiuu.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import io.github.lingjiuu.llm.LlmCallOptions;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.llm.ReasoningOptions;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

public class OpenAiResponsesRequestBuilder {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final OpenAiToolSchemaBuilder toolSchemaBuilder = new OpenAiToolSchemaBuilder();

    public ResponseCreateParams buildRequest(LlmRequest request) {
        if (request == null || request.getModel() == null) {
            throw new IllegalArgumentException("request model must not be null");
        }

        LlmCallOptions safeOptions = request.getCallOptions() == null ? LlmCallOptions.builder().build() : request.getCallOptions();
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(request.getModel().getId())
                .store(false)
                .inputOfResponse(toInputItems(request));

        if (safeOptions.getTemperature() != null) {
            builder.temperature(safeOptions.getTemperature());
        }
        if (safeOptions.getMaxTokens() != null) {
            builder.maxOutputTokens(safeOptions.getMaxTokens().longValue());
        }
        if (safeOptions.getReasoning() != null) {
            builder.reasoning(toOpenAiReasoning(safeOptions.getReasoning()));
        }

        if (request.getTools() != null) {
            for (ToolDefinition tool : request.getTools()) {
                if (tool != null) {
                    builder.addTool(toolSchemaBuilder.build(tool));
                }
            }
        }

        return builder.build();
    }

    private List<ResponseInputItem> toInputItems(LlmRequest request) {
        List<ResponseInputItem> inputItems = new ArrayList<>();

        String systemPrompt = request.getSystemPrompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            inputItems.add(ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.DEVELOPER)
                            .content(systemPrompt)
                            .build()
            ));
        }

        for (Message message : request.getMessages()) {
            switch (message.role()) {
                case USER -> appendUserMessage(inputItems, (UserMessage) message);
                case ASSISTANT -> appendAssistantMessage(inputItems, (AssistantMessage) message);
                case TOOLRESULT -> appendToolResultMessage(inputItems, (ToolResultMessage) message);
            }
        }

        return inputItems;
    }

    private void appendUserMessage(List<ResponseInputItem> inputItems, UserMessage userMessage) {
        String text = MessageContents.text(userMessage);
        if (!text.isBlank()) {
            inputItems.add(ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.USER)
                            .content(text)
                            .build()
            ));
        }
    }

    private void appendAssistantMessage(List<ResponseInputItem> inputItems, AssistantMessage assistantMessage) {
        if (assistantMessage.getProviderState() instanceof OpenAiReplayData replayData && replayData.getItems() != null
                && !replayData.getItems().isEmpty()) {
            appendReplayItems(inputItems, replayData);
            return;
        }

        int textIndex = 0;
        int reasoningIndex = 0;
        for (MessageContent content : assistantMessage.messageContents()) {
            if (content instanceof TextContent textContent) {
                String text = textContent.getText() == null ? "" : textContent.getText().trim();
                if (!text.isBlank()) {
                    inputItems.add(ResponseInputItem.ofResponseOutputMessage(toResponseOutputMessage(text, textIndex++)));
                }
            } else if (content instanceof ThinkingContent thinkingContent) {
                ResponseReasoningItem reasoningItem = toReasoningItem(thinkingContent, reasoningIndex++);
                if (reasoningItem != null) {
                    inputItems.add(ResponseInputItem.ofReasoning(reasoningItem));
                }
            } else if (content instanceof ToolCallContent toolCallContent) {
                inputItems.add(ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                                .callId(toolCallContent.getToolCallId())
                                .name(toolCallContent.getToolName())
                                .arguments(resolveArgumentsJson(toolCallContent))
                                .build()
                ));
            }
        }
    }

    private void appendReplayItems(List<ResponseInputItem> inputItems, OpenAiReplayData replayData) {
        for (OpenAiReplayData.ReplayItem item : replayData.getItems()) {
            if (item == null || item.getType() == null || item.getJson() == null || item.getJson().isBlank()) {
                continue;
            }
            try {
                switch (item.getType()) {
                    case OUTPUT_MESSAGE -> inputItems.add(ResponseInputItem.ofResponseOutputMessage(
                            objectMapper.readValue(item.getJson(), ResponseOutputMessage.class)
                    ));
                    case REASONING -> inputItems.add(ResponseInputItem.ofReasoning(
                            objectMapper.readValue(item.getJson(), ResponseReasoningItem.class)
                    ));
                    case FUNCTION_CALL -> inputItems.add(ResponseInputItem.ofFunctionCall(
                            objectMapper.readValue(item.getJson(), ResponseFunctionToolCall.class)
                    ));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void appendToolResultMessage(List<ResponseInputItem> inputItems, ToolResultMessage toolResultMessage) {
        inputItems.add(ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder()
                        .callId(toolResultMessage.getToolCallId())
                        .output(MessageContents.text(toolResultMessage))
                        .status(ResponseInputItem.FunctionCallOutput.Status.COMPLETED)
                        .build()
        ));
    }

    private ResponseOutputMessage toResponseOutputMessage(String text, int index) {
        return ResponseOutputMessage.builder()
                .id("msg_" + index)
                .status(ResponseOutputMessage.Status.COMPLETED)
                .role(JsonValue.from("assistant"))
                .addContent(ResponseOutputText.builder()
                        .text(text)
                        .annotations(List.of())
                        .build())
                .build();
    }

    private ResponseReasoningItem toReasoningItem(ThinkingContent thinkingContent, int index) {
        if (thinkingContent.getThinking() == null || thinkingContent.getThinking().isBlank()) {
            return null;
        }
        return ResponseReasoningItem.builder()
                .id("rs_" + index)
                .addSummary(ResponseReasoningItem.Summary.builder()
                        .text(thinkingContent.getThinking())
                        .build())
                .build();
    }

    private String resolveArgumentsJson(ToolCallContent toolCallContent) {
        if (toolCallContent.getArgumentsJson() != null && !toolCallContent.getArgumentsJson().isBlank()) {
            return toolCallContent.getArgumentsJson();
        }
        if (toolCallContent.getArguments() != null) {
            try {
                return objectMapper.writeValueAsString(toolCallContent.getArguments());
            } catch (Exception ignored) {
            }
        }
        return "{}";
    }

    private com.openai.models.Reasoning toOpenAiReasoning(ReasoningOptions reasoning) {
        com.openai.models.Reasoning.Builder builder = com.openai.models.Reasoning.builder();
        if (reasoning.getReasoningEffort() != null) {
            builder.effort(mapReasoningEffort(reasoning.getReasoningEffort()));
        }
        if (reasoning.getReasoningSummaryEffort() != null) {
            builder.generateSummary(mapSummaryEffort(reasoning.getReasoningSummaryEffort()));
        }
        return builder.build();
    }

    private ReasoningEffort mapReasoningEffort(ReasoningOptions.ReasoningEffort effort) {
        return switch (effort) {
            case NONE -> ReasoningEffort.NONE;
            case MINIMAL -> ReasoningEffort.MINIMAL;
            case LOW -> ReasoningEffort.LOW;
            case MEDIUM -> ReasoningEffort.MEDIUM;
            case HIGH -> ReasoningEffort.HIGH;
            case XHIGH -> ReasoningEffort.XHIGH;
        };
    }

    private com.openai.models.Reasoning.GenerateSummary mapSummaryEffort(ReasoningOptions.ReasoningSummaryEffort summaryEffort) {
        return switch (summaryEffort) {
            case AUTO -> com.openai.models.Reasoning.GenerateSummary.AUTO;
            case CONCISE -> com.openai.models.Reasoning.GenerateSummary.CONCISE;
            case DETAILED -> com.openai.models.Reasoning.GenerateSummary.DETAILED;
        };
    }
}
