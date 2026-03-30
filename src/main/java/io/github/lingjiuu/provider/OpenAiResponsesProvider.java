package io.github.lingjiuu.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import io.github.lingjiuu.ai.AiModel;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.Reasoning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiResponsesProvider implements Provider {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public AssistantMessageEventStream streamSimple(AiModel model, ProviderContext context, ProviderOptions options) {
        OpenAIClient client = createClient(model, options);
        ResponseCreateParams params = buildParams(model, context, options);
        return new OpenAiResponsesStream(
                client.responses().createStreaming(params),
                model.getId(),
                model.getProvider()
        );
    }

    private ResponseCreateParams buildParams(AiModel model, ProviderContext context, ProviderOptions options) {
        ProviderContext safeContext = context == null ? ProviderContext.builder().build() : context;
        ProviderOptions safeOptions = options == null ? ProviderOptions.builder().build() : options;

        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(model.getId())
                .store(false)
                .inputOfResponse(toInputItems(safeContext));

        if (safeOptions.getTemperature() != null) {
            builder.temperature(safeOptions.getTemperature());
        }
        if (safeOptions.getMaxTokens() != null) {
            builder.maxOutputTokens(safeOptions.getMaxTokens().longValue());
        }
        if (safeOptions.getReasoning() != null) {
            builder.reasoning(toOpenAiReasoning(safeOptions.getReasoning()));
        }

        for (ProviderTool tool : safeContext.getTools()) {
            builder.addTool(toFunctionTool(tool));
        }

        return builder.build();
    }

    private OpenAIClient createClient(AiModel model, ProviderOptions options) {
        String apiKey = options == null ? null : options.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("No API key for provider: " + model.getProvider());
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (model.getHeaders() != null) {
            headers.putAll(model.getHeaders());
        }
        if (options != null && options.getHeaders() != null) {
            headers.putAll(options.getHeaders());
        }

        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(model.getBaseUrl());
        if (!headers.isEmpty()) {
            headers.forEach(builder::putHeader);
        }
        return builder.build();
    }

    private List<ResponseInputItem> toInputItems(ProviderContext context) {
        List<ResponseInputItem> inputItems = new ArrayList<>();

        if (context.getSystemPrompt() != null && !context.getSystemPrompt().isBlank()) {
            inputItems.add(ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.DEVELOPER)
                            .content(context.getSystemPrompt())
                            .build()
            ));
        }

        for (Message message : context.getMessages()) {
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
        int textIndex = 0;
        int reasoningIndex = 0;

        for (MessageContent content : assistantMessage.messageContents()) {
            if (content instanceof TextContent textContent) {
                String text = textContent.getText() == null ? "" : textContent.getText().trim();
                if (!text.isBlank()) {
                    inputItems.add(ResponseInputItem.ofResponseOutputMessage(
                            toResponseOutputMessage(textContent, textIndex++)
                    ));
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

    private void appendToolResultMessage(List<ResponseInputItem> inputItems, ToolResultMessage toolResultMessage) {
        inputItems.add(ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder()
                        .callId(toolResultMessage.getToolCallId())
                        .output(MessageContents.text(toolResultMessage))
                        .status(ResponseInputItem.FunctionCallOutput.Status.COMPLETED)
                        .build()
        ));
    }

    private ResponseOutputMessage toResponseOutputMessage(TextContent textContent, int index) {
        return ResponseOutputMessage.builder()
                .id(resolveTextMessageId(textContent, index))
                .status(ResponseOutputMessage.Status.COMPLETED)
                .role(JsonValue.from("assistant"))
                .addContent(ResponseOutputText.builder()
                        .text(textContent.getText())
                        .annotations(List.of())
                        .build())
                .build();
    }

    private ResponseReasoningItem toReasoningItem(ThinkingContent thinkingContent, int index) {
        if (thinkingContent.getThinkingSignature() != null && !thinkingContent.getThinkingSignature().isBlank()) {
            try {
                return objectMapper.readValue(thinkingContent.getThinkingSignature(), ResponseReasoningItem.class);
            } catch (Exception ignored) {
            }
        }

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

    private String resolveTextMessageId(TextContent textContent, int index) {
        String signature = textContent.getTextSignature();
        if (signature == null || signature.isBlank()) {
            return "msg_" + index;
        }

        try {
            JsonNode node = objectMapper.readTree(signature);
            if (node.hasNonNull("id")) {
                return node.get("id").asText();
            }
        } catch (Exception ignored) {
        }

        return signature;
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

    private com.openai.models.Reasoning toOpenAiReasoning(Reasoning reasoning) {
        com.openai.models.Reasoning.Builder builder = com.openai.models.Reasoning.builder();
        if (reasoning.getReasoningEffort() != null) {
            builder.effort(mapReasoningEffort(reasoning.getReasoningEffort()));
        }
        if (reasoning.getReasoningSummaryEffort() != null) {
            builder.generateSummary(mapSummaryEffort(reasoning.getReasoningSummaryEffort()));
        }
        return builder.build();
    }

    private ReasoningEffort mapReasoningEffort(Reasoning.ReasoningEffort effort) {
        return switch (effort) {
            case NONE -> ReasoningEffort.NONE;
            case MINIMAL -> ReasoningEffort.MINIMAL;
            case LOW -> ReasoningEffort.LOW;
            case MEDIUM -> ReasoningEffort.MEDIUM;
            case HIGH -> ReasoningEffort.HIGH;
            case XHIGH -> ReasoningEffort.XHIGH;
        };
    }

    private com.openai.models.Reasoning.GenerateSummary mapSummaryEffort(Reasoning.ReasoningSummaryEffort summaryEffort) {
        return switch (summaryEffort) {
            case AUTO -> com.openai.models.Reasoning.GenerateSummary.AUTO;
            case CONCISE -> com.openai.models.Reasoning.GenerateSummary.CONCISE;
            case DETAILED -> com.openai.models.Reasoning.GenerateSummary.DETAILED;
        };
    }

    private FunctionTool toFunctionTool(ProviderTool tool) {
        FunctionTool.Builder builder = FunctionTool.builder()
                .name(tool.getName());

        if (tool.getDescription() != null && !tool.getDescription().isBlank()) {
            builder.description(tool.getDescription());
        }
        if (tool.getStrict() != null) {
            builder.strict(tool.getStrict());
        }
        if (tool.getParameters() != null && tool.getParameters().isObject()) {
            FunctionTool.Parameters.Builder parametersBuilder = FunctionTool.Parameters.builder();
            tool.getParameters().fields().forEachRemaining(entry ->
                    parametersBuilder.putAdditionalProperty(entry.getKey(), JsonValue.fromJsonNode(entry.getValue()))
            );
            builder.parameters(parametersBuilder.build());
        }

        return builder.build();
    }

}
