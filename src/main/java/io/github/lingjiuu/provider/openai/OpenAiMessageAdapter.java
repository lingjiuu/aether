package io.github.lingjiuu.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
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

import java.util.ArrayList;
import java.util.List;

public class OpenAiMessageAdapter {

    private static final String EMPTY_ASSISTANT_PLACEHOLDER = "[No assistant content]";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public List<ResponseInputItem> toInputItems(String systemPrompt, List<Message> messages) {
        List<ResponseInputItem> inputItems = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            appendEasyInputMessage(inputItems, EasyInputMessage.Role.DEVELOPER, systemPrompt);
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
        if (assistantMessage.getProviderState() instanceof OpenAiReplayData replayData
                && replayData.getItems() != null
                && !replayData.getItems().isEmpty()) {
            appendReplayItems(inputItems, replayData);
            return new AssistantIndexes(textIndex, reasoningIndex);
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
                String toolCallId = safeText(toolCallContent.getToolCallId());
                inputItems.add(ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                                .callId(toolCallId)
                                .name(safeText(toolCallContent.getToolName()))
                                .arguments(resolveArgumentsJson(toolCallContent))
                                .build()
                ));
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

    private void appendReplayItems(List<ResponseInputItem> inputItems, OpenAiReplayData replayData) {
        for (OpenAiReplayData.ReplayItem item : replayData.getItems()) {
            if (item == null || item.getType() == null || item.getJson() == null || item.getJson().isBlank()) {
                continue;
            }
            try {
                String json = OpenAiReplayJsonSanitizer.sanitize(item.getJson(), objectMapper);
                switch (item.getType()) {
                    case OUTPUT_MESSAGE -> inputItems.add(ResponseInputItem.ofResponseOutputMessage(
                            objectMapper.readValue(json, ResponseOutputMessage.class)
                    ));
                    case REASONING -> inputItems.add(ResponseInputItem.ofReasoning(
                            objectMapper.readValue(json, ResponseReasoningItem.class)
                    ));
                    case FUNCTION_CALL -> inputItems.add(ResponseInputItem.ofFunctionCall(
                            objectMapper.readValue(json, ResponseFunctionToolCall.class)
                    ));
                }
            } catch (Exception ignored) {
            }
        }
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
                if (imageContent.getData() != null
                        && !imageContent.getData().isBlank()
                        && imageContent.getMimeType() != null
                        && !imageContent.getMimeType().isBlank()) {
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
            if (imageContent.getData() == null
                    || imageContent.getData().isBlank()
                    || imageContent.getMimeType() == null
                    || imageContent.getMimeType().isBlank()) {
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

    private String toDataUrl(ImageContent imageContent) {
        return "data:" + imageContent.getMimeType() + ";base64," + imageContent.getData();
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
