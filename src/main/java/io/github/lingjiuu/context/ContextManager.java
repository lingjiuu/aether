package io.github.lingjiuu.context;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ContextManager {

    private static final String MISSING_TOOL_RESULT_TEXT = "aborted";
    private static final String IMAGE_CONTENT_OMITTED_PLACEHOLDER =
            "image content omitted because you do not support image input";

    private final List<Message> messages = new ArrayList<>();

    public ContextManager() {
        this(List.of());
    }

    public ContextManager(Collection<? extends Message> initialMessages) {
        recordAll(initialMessages);
    }

    public synchronized void record(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        messages.add(message);
    }

    public synchronized void recordAll(Collection<? extends Message> newMessages) {
        if (newMessages == null) {
            return;
        }
        for (Message message : newMessages) {
            record(message);
        }
    }

    public synchronized void replaceAll(Collection<? extends Message> replacementMessages) {
        messages.clear();
        recordAll(replacementMessages);
    }

    public synchronized void clear() {
        messages.clear();
    }

    public synchronized List<Message> snapshot() {
        return List.copyOf(messages);
    }

    public synchronized Message lastMessage() {
        return messages.isEmpty() ? null : messages.getLast();
    }

    public synchronized boolean canContinue() {
        Message lastMessage = lastMessage();
        return lastMessage != null && lastMessage.role() != Message.Role.ASSISTANT;
    }

    public synchronized List<Message> normalizeMessagesForModel(List<String> inputModalities) {
        return normalizeMessagesForModel(messages, inputModalities);
    }

    public List<Message> normalizeMessagesForModel(
            Collection<? extends Message> sourceMessages,
            List<String> inputModalities
    ) {
        if (sourceMessages == null || sourceMessages.isEmpty()) {
            return List.of();
        }
        List<Message> withMissingToolResults = ensureToolResultsPresent(sourceMessages);
        List<Message> withoutOrphanToolResults = removeOrphanToolResults(withMissingToolResults);
        return stripImagesWhenUnsupported(withoutOrphanToolResults, inputModalities);
    }

    public synchronized long estimateTokensForModel(String baseInstructions) {
        long chars = visibleChars(baseInstructions);
        for (Message message : messages) {
            chars += visibleChars(message);
        }
        return approxTokens(chars);
    }

    public synchronized long estimateTokensAfterLastAssistantMessage() {
        int lastAssistantIndex = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof AssistantMessage) {
                lastAssistantIndex = index;
                break;
            }
        }
        if (lastAssistantIndex < 0) {
            return -1;
        }
        if (lastAssistantIndex >= messages.size() - 1) {
            return 0;
        }

        long chars = 0;
        for (int index = lastAssistantIndex + 1; index < messages.size(); index++) {
            chars += visibleChars(messages.get(index));
        }
        return approxTokens(chars);
    }

    private List<Message> ensureToolResultsPresent(Collection<? extends Message> sourceMessages) {
        List<Message> normalized = new ArrayList<>();
        Map<String, String> pendingToolCalls = new LinkedHashMap<>();
        Set<String> seenToolCallIds = new LinkedHashSet<>();

        for (Message message : sourceMessages) {
            if (message == null || message.role() == null) {
                continue;
            }

            if (shouldFlushPendingToolResults(message)) {
                appendPendingToolResults(normalized, pendingToolCalls);
            }

            normalized.add(message);

            if (message instanceof AssistantMessage assistantMessage) {
                collectToolCalls(assistantMessage, seenToolCallIds, pendingToolCalls);
            } else if (message instanceof ToolResultMessage toolResultMessage) {
                pendingToolCalls.remove(safeText(toolResultMessage.getToolCallId()));
            }
        }

        appendPendingToolResults(normalized, pendingToolCalls);
        return List.copyOf(normalized);
    }

    private List<Message> removeOrphanToolResults(Collection<? extends Message> sourceMessages) {
        List<Message> normalized = new ArrayList<>();
        Set<String> seenToolCallIds = new LinkedHashSet<>();
        Set<String> seenToolResultIds = new LinkedHashSet<>();

        for (Message message : sourceMessages) {
            if (message == null || message.role() == null) {
                continue;
            }

            if (message instanceof AssistantMessage assistantMessage) {
                normalized.add(message);
                collectToolCalls(assistantMessage, seenToolCallIds, new LinkedHashMap<>());
                continue;
            }

            if (message instanceof ToolResultMessage toolResultMessage) {
                String toolCallId = safeText(toolResultMessage.getToolCallId());
                if (toolCallId.isBlank()
                        || !seenToolCallIds.contains(toolCallId)
                        || seenToolResultIds.contains(toolCallId)) {
                    continue;
                }
                seenToolResultIds.add(toolCallId);
            }

            normalized.add(message);
        }

        return List.copyOf(normalized);
    }

    private List<Message> stripImagesWhenUnsupported(
            Collection<? extends Message> sourceMessages,
            List<String> inputModalities
    ) {
        if (supportsImageInput(inputModalities)) {
            return List.copyOf(sourceMessages);
        }

        List<Message> normalized = new ArrayList<>();
        for (Message message : sourceMessages) {
            if (message == null) {
                continue;
            }
            normalized.add(replaceImages(message));
        }
        return List.copyOf(normalized);
    }

    private void collectToolCalls(
            AssistantMessage assistantMessage,
            Set<String> seenToolCallIds,
            Map<String, String> pendingToolCalls
    ) {
        if (assistantMessage == null || assistantMessage.messageContents() == null) {
            return;
        }
        for (MessageContent content : assistantMessage.messageContents()) {
            if (!(content instanceof ToolCallContent toolCallContent)) {
                continue;
            }
            String toolCallId = safeText(toolCallContent.getToolCallId());
            if (toolCallId.isBlank() || seenToolCallIds.contains(toolCallId)) {
                continue;
            }
            seenToolCallIds.add(toolCallId);
            if (pendingToolCalls != null) {
                pendingToolCalls.put(toolCallId, safeText(toolCallContent.getToolName()));
            }
        }
    }

    private boolean shouldFlushPendingToolResults(Message message) {
        if (message.role() == Message.Role.USER || message.role() == Message.Role.CONTEXT) {
            return true;
        }
        if (message instanceof AssistantMessage assistantMessage) {
            return !isPureToolCallAssistant(assistantMessage);
        }
        return false;
    }

    private boolean isPureToolCallAssistant(AssistantMessage assistantMessage) {
        if (assistantMessage == null
                || assistantMessage.messageContents() == null
                || assistantMessage.messageContents().isEmpty()) {
            return false;
        }
        for (MessageContent content : assistantMessage.messageContents()) {
            if (!(content instanceof ToolCallContent)) {
                return false;
            }
        }
        return true;
    }

    private void appendPendingToolResults(
            List<Message> normalized,
            Map<String, String> pendingToolCalls
    ) {
        if (pendingToolCalls.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : pendingToolCalls.entrySet()) {
            normalized.add(ToolResultMessage.builder()
                    .toolCallId(entry.getKey())
                    .toolName(entry.getValue())
                    .isError(true)
                    .contents(List.of(TextContent.builder()
                            .text(MISSING_TOOL_RESULT_TEXT)
                            .build()))
                    .build());
        }
        pendingToolCalls.clear();
    }

    private Message replaceImages(Message message) {
        if (!hasImages(message.messageContents())) {
            return message;
        }

        List<MessageContent> contents = replaceImageContents(message.messageContents());
        return switch (message.role()) {
            case USER -> copyUserMessage((UserMessage) message, contents);
            case ASSISTANT -> copyAssistantMessage((AssistantMessage) message, contents);
            case TOOLRESULT -> copyToolResultMessage((ToolResultMessage) message, contents);
            case CONTEXT -> copyContextMessage((ContextMessage) message, contents);
        };
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

    private List<MessageContent> replaceImageContents(List<MessageContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        List<MessageContent> normalized = new ArrayList<>(contents.size());
        for (MessageContent content : contents) {
            if (content instanceof ImageContent) {
                normalized.add(TextContent.builder()
                        .text(IMAGE_CONTENT_OMITTED_PLACEHOLDER)
                        .build());
            } else {
                normalized.add(content);
            }
        }
        return List.copyOf(normalized);
    }

    private UserMessage copyUserMessage(UserMessage message, List<MessageContent> contents) {
        return UserMessage.builder()
                .id(message.getId())
                .timestamp(message.getTimestamp())
                .contents(contents)
                .build();
    }

    private AssistantMessage copyAssistantMessage(AssistantMessage message, List<MessageContent> contents) {
        return AssistantMessage.builder()
                .id(message.getId())
                .timestamp(message.getTimestamp())
                .responseId(message.getResponseId())
                .provider(message.getProvider())
                .model(message.getModel())
                .contents(contents)
                .stopReason(message.getStopReason())
                .usage(message.getUsage())
                .errorMessage(message.getErrorMessage())
                .providerState(message.getProviderState())
                .build();
    }

    private ToolResultMessage copyToolResultMessage(ToolResultMessage message, List<MessageContent> contents) {
        return ToolResultMessage.builder()
                .id(message.getId())
                .timestamp(message.getTimestamp())
                .contents(contents)
                .toolCallId(message.getToolCallId())
                .toolName(message.getToolName())
                .details(message.getDetails())
                .isError(message.isError())
                .build();
    }

    private ContextMessage copyContextMessage(ContextMessage message, List<MessageContent> contents) {
        return ContextMessage.builder()
                .id(message.getId())
                .timestamp(message.getTimestamp())
                .kind(message.getKind())
                .contents(contents)
                .build();
    }

    private boolean supportsImageInput(List<String> inputModalities) {
        if (inputModalities == null || inputModalities.isEmpty()) {
            return false;
        }
        for (String modality : inputModalities) {
            String normalized = safeText(modality).toLowerCase();
            if ("image".equals(normalized) || "vision".equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private long visibleChars(Message message) {
        if (message == null) {
            return 0;
        }

        long chars = message.role() == null ? 0 : message.role().name().length();
        List<MessageContent> contents = message.messageContents();
        if (contents != null) {
            for (MessageContent content : contents) {
                chars += visibleChars(content);
            }
        }
        return chars;
    }

    private long visibleChars(MessageContent content) {
        if (content instanceof TextContent textContent) {
            return visibleChars(textContent.getText());
        }
        if (content instanceof ThinkingContent thinkingContent) {
            return visibleChars(thinkingContent.getThinking());
        }
        if (content instanceof ToolCallContent toolCallContent) {
            String arguments = toolCallContent.getArguments() == null
                    ? null
                    : toolCallContent.getArguments().toString();
            return visibleChars(toolCallContent.getToolName())
                    + visibleChars(toolCallContent.getArgumentsJson())
                    + visibleChars(arguments);
        }
        if (content instanceof ImageContent imageContent) {
            return visibleChars(imageContent.getMimeType()) + visibleChars(imageContent.getData());
        }
        return 0;
    }

    private long visibleChars(String text) {
        return text == null ? 0 : text.length();
    }

    private long approxTokens(long chars) {
        if (chars <= 0) {
            return 0;
        }
        return Math.max(1, (chars + 3) / 4);
    }
}
