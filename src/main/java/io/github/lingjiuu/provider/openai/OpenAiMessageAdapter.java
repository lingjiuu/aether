package io.github.lingjiuu.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseReasoningItem;
import io.github.lingjiuu.provider.protocol.NormalizedAssistantMessage;
import io.github.lingjiuu.provider.protocol.NormalizedContent;
import io.github.lingjiuu.provider.protocol.NormalizedContextMessage;
import io.github.lingjiuu.provider.protocol.NormalizedRequestMessage;
import io.github.lingjiuu.provider.protocol.NormalizedTextContent;
import io.github.lingjiuu.provider.protocol.NormalizedThinkingContent;
import io.github.lingjiuu.provider.protocol.NormalizedToolCallContent;
import io.github.lingjiuu.provider.protocol.NormalizedToolResultContent;
import io.github.lingjiuu.provider.protocol.NormalizedUserMessage;

import java.util.ArrayList;
import java.util.List;

public class OpenAiMessageAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public List<ResponseInputItem> toInputItems(String systemPrompt, List<NormalizedRequestMessage> messages) {
        List<ResponseInputItem> inputItems = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            inputItems.add(ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.DEVELOPER)
                            .content(systemPrompt)
                            .build()
            ));
        }

        if (messages == null || messages.isEmpty()) {
            return inputItems;
        }

        int assistantTextIndex = 0;
        int reasoningIndex = 0;
        for (NormalizedRequestMessage message : messages) {
            switch (message.kind()) {
                case USER -> appendUserMessage(inputItems, (NormalizedUserMessage) message);
                case ASSISTANT -> {
                    AssistantIndexes indexes = appendAssistantMessage(
                            inputItems,
                            (NormalizedAssistantMessage) message,
                            assistantTextIndex,
                            reasoningIndex
                    );
                    assistantTextIndex = indexes.textIndex();
                    reasoningIndex = indexes.reasoningIndex();
                }
                case CONTEXT -> appendContextMessage(inputItems, (NormalizedContextMessage) message);
            }
        }

        return inputItems;
    }

    private void appendUserMessage(List<ResponseInputItem> inputItems, NormalizedUserMessage userMessage) {
        appendUserText(inputItems, textOf(userMessage.contents()));
    }

    private AssistantIndexes appendAssistantMessage(
            List<ResponseInputItem> inputItems,
            NormalizedAssistantMessage assistantMessage,
            int textIndex,
            int reasoningIndex
    ) {
        if (assistantMessage.providerState() instanceof OpenAiReplayData replayData
                && replayData.getItems() != null
                && !replayData.getItems().isEmpty()) {
            appendReplayItems(inputItems, replayData);
            return new AssistantIndexes(textIndex, reasoningIndex);
        }

        int nextTextIndex = textIndex;
        int nextReasoningIndex = reasoningIndex;
        for (NormalizedContent content : assistantMessage.contents()) {
            if (content instanceof NormalizedTextContent textContent) {
                String text = textContent.text().trim();
                if (!text.isBlank()) {
                    inputItems.add(ResponseInputItem.ofResponseOutputMessage(toResponseOutputMessage(text, nextTextIndex++)));
                }
            } else if (content instanceof NormalizedThinkingContent thinkingContent) {
                ResponseReasoningItem reasoningItem = toReasoningItem(thinkingContent, nextReasoningIndex++);
                if (reasoningItem != null) {
                    inputItems.add(ResponseInputItem.ofReasoning(reasoningItem));
                }
            } else if (content instanceof NormalizedToolCallContent toolCallContent) {
                inputItems.add(ResponseInputItem.ofFunctionCall(
                        ResponseFunctionToolCall.builder()
                                .callId(toolCallContent.toolCallId())
                                .name(toolCallContent.toolName())
                                .arguments(toolCallContent.argumentsJson())
                                .build()
                ));
            }
        }

        return new AssistantIndexes(nextTextIndex, nextReasoningIndex);
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

    private void appendContextMessage(List<ResponseInputItem> inputItems, NormalizedContextMessage contextMessage) {
        List<String> pendingText = new ArrayList<>();
        for (NormalizedContent content : contextMessage.contents()) {
            if (content instanceof NormalizedToolResultContent toolResultContent) {
                appendUserText(inputItems, joinedText(pendingText));
                pendingText.clear();
                inputItems.add(ResponseInputItem.ofFunctionCallOutput(
                        ResponseInputItem.FunctionCallOutput.builder()
                                .callId(toolResultContent.toolCallId())
                                .output(toolResultContent.outputText())
                                .status(ResponseInputItem.FunctionCallOutput.Status.COMPLETED)
                                .build()
                ));
            } else if (content instanceof NormalizedTextContent textContent && !textContent.text().isBlank()) {
                pendingText.add(textContent.text());
            }
        }
        appendUserText(inputItems, joinedText(pendingText));
    }

    private void appendUserText(List<ResponseInputItem> inputItems, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        inputItems.add(ResponseInputItem.ofEasyInputMessage(
                EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content(text)
                        .build()
        ));
    }

    private String textOf(List<NormalizedContent> contents) {
        List<String> textParts = new ArrayList<>();
        for (NormalizedContent content : contents) {
            if (content instanceof NormalizedTextContent textContent && !textContent.text().isBlank()) {
                textParts.add(textContent.text());
            }
        }
        return joinedText(textParts);
    }

    private String joinedText(List<String> textParts) {
        if (textParts == null || textParts.isEmpty()) {
            return "";
        }
        return String.join("\n", textParts).trim();
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

    private ResponseReasoningItem toReasoningItem(NormalizedThinkingContent thinkingContent, int index) {
        if (thinkingContent.thinking() == null || thinkingContent.thinking().isBlank()) {
            return null;
        }
        return ResponseReasoningItem.builder()
                .id("rs_" + index)
                .addSummary(ResponseReasoningItem.Summary.builder()
                        .text(thinkingContent.thinking())
                        .build())
                .build();
    }

    private record AssistantIndexes(int textIndex, int reasoningIndex) {
    }
}
