package io.github.lingjiuu.context;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ContextManager {

    private final List<Message> messages = new ArrayList<>();
    private final ContextPolicy policy;

    public ContextManager() {
        this(ContextPolicy.defaults(), List.of());
    }

    public ContextManager(ContextPolicy policy, Collection<? extends Message> initialMessages) {
        this.policy = policy == null ? ContextPolicy.defaults() : policy;
        recordAll(initialMessages);
    }

    public synchronized void record(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        messages.add(normalizeForHistory(message));
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

    public ContextProjection normalizeMessagesForModel() {
        return new ContextProjection(snapshot());
    }

    public ContextPolicy policy() {
        return policy;
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

    private Message normalizeForHistory(Message message) {
        if (!(message instanceof ToolResultMessage toolResultMessage)) {
            return message;
        }

        String text = MessageContents.text(toolResultMessage);
        if (text == null || text.length() <= policy.maxToolResultChars()) {
            return message;
        }

        String truncatedText = text.substring(0, policy.maxToolResultChars())
                + "\n\n[tool result truncated by context policy]";
        List<MessageContent> contents = List.of(TextContent.builder()
                .text(truncatedText)
                .build());
        return ToolResultMessage.builder()
                .id(toolResultMessage.getId())
                .timestamp(toolResultMessage.getTimestamp())
                .toolCallId(toolResultMessage.getToolCallId())
                .toolName(toolResultMessage.getToolName())
                .details(toolResultMessage.getDetails())
                .isError(toolResultMessage.isError())
                .contents(contents)
                .build();
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
