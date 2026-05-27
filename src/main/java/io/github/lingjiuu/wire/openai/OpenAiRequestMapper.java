package io.github.lingjiuu.wire.openai;

import com.openai.core.JsonValue;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionCallOutputItem;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputImageContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseInputTextContent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.ModelInfo;
import io.github.lingjiuu.model.ReasoningOptions;
import io.github.lingjiuu.model.client.ModelCallOptions;
import io.github.lingjiuu.model.client.ModelRequest;
import io.github.lingjiuu.tool.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OpenAiRequestMapper {

    private static final String EMPTY_ASSISTANT_PLACEHOLDER = "[No assistant content]";

    private final OpenAiReplayCodec replayCodec;

    public OpenAiRequestMapper() {
        this(new OpenAiReplayCodec());
    }

    OpenAiRequestMapper(OpenAiReplayCodec replayCodec) {
        this.replayCodec = replayCodec;
    }

    public ResponseCreateParams buildRequest(ModelRequest request, ModelInfo model) {
        if (request == null || model == null) {
            throw new IllegalArgumentException("request and model must not be null");
        }

        ModelCallOptions safeOptions = request.getCallOptions() == null
                ? ModelCallOptions.builder().build()
                : request.getCallOptions();
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(model.getId())
                .store(false)
                .inputOfResponse(toInputItems(request.getBaseInstructions(), request.getMessages()));

        if (safeOptions.getTemperature() != null) {
            builder.temperature(safeOptions.getTemperature());
        }
        if (safeOptions.getMaxTokens() != null) {
            builder.maxOutputTokens(safeOptions.getMaxTokens().longValue());
        }
        if (safeOptions.getReasoning() != null) {
            builder.reasoning(toOpenAiReasoning(safeOptions.getReasoning()));
        }

        if (request.getTools() != null && !request.getTools().isEmpty()) {
            builder.parallelToolCalls(true);
            for (Tool tool : request.getTools()) {
                if (tool != null) {
                    builder.addTool(toOpenAiTool(tool));
                }
            }
        }

        return builder.build();
    }

    private List<ResponseInputItem> toInputItems(String baseInstructions, List<Message> messages) {
        List<ResponseInputItem> inputItems = new ArrayList<>();

        if (baseInstructions != null && !baseInstructions.isBlank()) {
            appendEasyInputMessage(inputItems, EasyInputMessage.Role.DEVELOPER, baseInstructions);
        }

        if (messages == null || messages.isEmpty()) {
            return inputItems;
        }

        int assistantTextIndex = 0;
        int reasoningIndex = 0;
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            switch (message.role()) {
                case USER -> appendUserMessage(inputItems, (UserMessage) message);
                case ASSISTANT -> {
                    AssistantIndexes indexes = appendAssistantMessage(
                            inputItems,
                            (AssistantMessage) message,
                            assistantTextIndex,
                            reasoningIndex
                    );
                    assistantTextIndex = indexes.textIndex();
                    reasoningIndex = indexes.reasoningIndex();
                }
                case TOOLRESULT -> appendToolResult(inputItems, (ToolResultMessage) message);
                case CONTEXT -> appendContextMessage(inputItems, message);
            }
        }
        return inputItems;
    }

    private void appendUserMessage(List<ResponseInputItem> inputItems, UserMessage userMessage) {
        List<MessageContent> contents = userMessage.messageContents();
        if (hasImages(contents)) {
            appendEasyInputMessage(inputItems, EasyInputMessage.Role.USER, toResponseInputContentList(contents));
            return;
        }
        appendUserText(inputItems, MessageContents.text(userMessage));
    }

    private AssistantIndexes appendAssistantMessage(
            List<ResponseInputItem> inputItems,
            AssistantMessage assistantMessage,
            int textIndex,
            int reasoningIndex
    ) {
        if (assistantMessage.getProviderState() instanceof OpenAiReplayData replayData) {
            List<ResponseInputItem> replayItems = replayCodec.toInputItems(replayData);
            if (!replayItems.isEmpty()) {
                inputItems.addAll(replayItems);
                return new AssistantIndexes(textIndex, reasoningIndex);
            }
        }

        int nextTextIndex = textIndex;
        int nextReasoningIndex = reasoningIndex;
        int appendedContentCount = 0;
        for (MessageContent content : assistantMessage.messageContents()) {
            if (content instanceof TextContent textContent) {
                String text = safeText(textContent.getText());
                if (!text.isBlank()) {
                    inputItems.add(ResponseInputItem.ofResponseOutputMessage(toResponseOutputMessage(text, nextTextIndex++)));
                    appendedContentCount++;
                }
            } else if (content instanceof ThinkingContent thinkingContent) {
                ResponseReasoningItem reasoningItem = toReasoningItem(thinkingContent.getThinking(), nextReasoningIndex++);
                if (reasoningItem != null) {
                    inputItems.add(ResponseInputItem.ofReasoning(reasoningItem));
                    appendedContentCount++;
                }
            } else if (content instanceof ToolCallContent toolCallContent) {
                inputItems.add(ResponseInputItem.ofFunctionCall(toFunctionToolCall(toolCallContent)));
                appendedContentCount++;
            }
        }

        if (appendedContentCount == 0) {
            inputItems.add(ResponseInputItem.ofResponseOutputMessage(toResponseOutputMessage(
                    EMPTY_ASSISTANT_PLACEHOLDER,
                    nextTextIndex++
            )));
        }
        return new AssistantIndexes(nextTextIndex, nextReasoningIndex);
    }

    private void appendToolResult(List<ResponseInputItem> inputItems, ToolResultMessage toolResultMessage) {
        String toolCallId = safeText(toolResultMessage.getToolCallId());
        if (toolCallId.isBlank()) {
            return;
        }
        inputItems.add(ResponseInputItem.ofFunctionCallOutput(toFunctionCallOutput(toolResultMessage)));
    }

    private void appendContextMessage(List<ResponseInputItem> inputItems, Message message) {
        List<MessageContent> contents = message.messageContents();
        if (hasImages(contents)) {
            appendEasyInputMessage(inputItems, EasyInputMessage.Role.USER, toResponseInputContentList(contents));
            return;
        }
        appendUserText(inputItems, MessageContents.text(message));
    }

    private void appendUserText(List<ResponseInputItem> inputItems, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        appendEasyInputMessage(inputItems, EasyInputMessage.Role.USER, text);
    }

    private void appendEasyInputMessage(
            List<ResponseInputItem> inputItems,
            EasyInputMessage.Role role,
            String text
    ) {
        inputItems.add(ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                        .role(role)
                        .content(text)
                        .build()
        ));
    }

    private void appendEasyInputMessage(
            List<ResponseInputItem> inputItems,
            EasyInputMessage.Role role,
            List<ResponseInputContent> contents
    ) {
        if (contents == null || contents.isEmpty()) {
            return;
        }
        inputItems.add(ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                        .role(role)
                        .contentOfResponseInputMessageContentList(contents)
                        .build()
        ));
    }

    private boolean hasImages(List<MessageContent> contents) {
        if (contents == null) {
            return false;
        }
        for (MessageContent content : contents) {
            if (content instanceof ImageContent) {
                return true;
            }
        }
        return false;
    }

    private List<ResponseInputContent> toResponseInputContentList(List<MessageContent> contents) {
        List<ResponseInputContent> inputContents = new ArrayList<>();
        if (contents == null) {
            return inputContents;
        }
        for (MessageContent content : contents) {
            if (content instanceof TextContent textContent) {
                String text = safeText(textContent.getText());
                if (!text.isBlank()) {
                    inputContents.add(ResponseInputContent.ofInputText(ResponseInputText.builder()
                            .text(text)
                            .build()));
                }
            } else if (content instanceof ImageContent imageContent) {
                if (hasImageData(imageContent)) {
                    inputContents.add(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                            .detail(ResponseInputImage.Detail.AUTO)
                            .imageUrl(toDataUrl(imageContent))
                            .build()));
                }
            }
        }
        return inputContents;
    }

    private ResponseInputItem.FunctionCallOutput toFunctionCallOutput(ToolResultMessage toolResultMessage) {
        List<ImageContent> images = imagesOf(toolResultMessage.messageContents());
        if (images.isEmpty()) {
            return ResponseInputItem.FunctionCallOutput.builder()
                    .callId(safeText(toolResultMessage.getToolCallId()))
                    .output(MessageContents.text(toolResultMessage))
                    .status(ResponseInputItem.FunctionCallOutput.Status.COMPLETED)
                    .build();
        }

        List<ResponseFunctionCallOutputItem> outputItems = new ArrayList<>();
        String outputText = MessageContents.text(toolResultMessage);
        if (!outputText.isBlank()) {
            outputItems.add(ResponseFunctionCallOutputItem.ofInputText(ResponseInputTextContent.builder()
                    .text(outputText)
                    .build()));
        }
        for (ImageContent imageContent : images) {
            if (!hasImageData(imageContent)) {
                continue;
            }
            outputItems.add(ResponseFunctionCallOutputItem.ofInputImage(ResponseInputImageContent.builder()
                    .detail(ResponseInputImageContent.Detail.AUTO)
                    .imageUrl(toDataUrl(imageContent))
                    .build()));
        }
        return ResponseInputItem.FunctionCallOutput.builder()
                .callId(safeText(toolResultMessage.getToolCallId()))
                .outputOfResponseFunctionCallOutputItemList(outputItems)
                .status(ResponseInputItem.FunctionCallOutput.Status.COMPLETED)
                .build();
    }

    private List<ImageContent> imagesOf(List<MessageContent> contents) {
        List<ImageContent> images = new ArrayList<>();
        if (contents == null) {
            return images;
        }
        for (MessageContent content : contents) {
            if (content instanceof ImageContent imageContent) {
                images.add(imageContent);
            }
        }
        return images;
    }

    private ResponseFunctionToolCall toFunctionToolCall(ToolCallContent toolCallContent) {
        return ResponseFunctionToolCall.builder()
                .callId(safeText(toolCallContent.getToolCallId()))
                .name(safeText(toolCallContent.getToolName()))
                .arguments(resolveArgumentsJson(toolCallContent))
                .build();
    }

    private FunctionTool toOpenAiTool(Tool tool) {
        FunctionTool.Parameters.Builder parametersBuilder = FunctionTool.Parameters.builder();
        Map<String, Object> parametersSchema = tool.parametersSchema();
        if (parametersSchema != null) {
            for (Map.Entry<String, Object> entry : parametersSchema.entrySet()) {
                parametersBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
            }
        }

        return FunctionTool.builder()
                .name(tool.name())
                .description(tool.description())
                .strict(false)
                .parameters(parametersBuilder.build())
                .build();
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

    private ResponseReasoningItem toReasoningItem(String thinking, int index) {
        if (thinking == null || thinking.isBlank()) {
            return null;
        }
        return ResponseReasoningItem.builder()
                .id("rs_" + index)
                .addSummary(ResponseReasoningItem.Summary.builder()
                        .text(thinking)
                        .build())
                .build();
    }

    private boolean hasImageData(ImageContent imageContent) {
        return imageContent.getData() != null
                && !imageContent.getData().isBlank()
                && imageContent.getMimeType() != null
                && !imageContent.getMimeType().isBlank();
    }

    private String toDataUrl(ImageContent imageContent) {
        return "data:" + imageContent.getMimeType() + ";base64," + imageContent.getData();
    }

    private String resolveArgumentsJson(ToolCallContent toolCallContent) {
        if (toolCallContent.getArgumentsJson() != null && !toolCallContent.getArgumentsJson().isBlank()) {
            return toolCallContent.getArgumentsJson();
        }
        if (toolCallContent.getArguments() != null) {
            return toolCallContent.getArguments().toString();
        }
        return "{}";
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private record AssistantIndexes(int textIndex, int reasoningIndex) {
    }
}
