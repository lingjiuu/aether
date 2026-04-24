package io.github.lingjiuu.provider.protocol;

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

public class DefaultRequestMessageNormalizer implements RequestMessageNormalizer {

    @Override
    public List<NormalizedRequestMessage> normalize(List<Message> messages, List<ToolDefinition> tools) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<NormalizedRequestMessage> normalizedMessages = new ArrayList<>();
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            switch (message.role()) {
                case USER -> appendMerged(normalizedMessages, normalizeUserMessage((UserMessage) message));
                case ASSISTANT -> appendAssistantMessage(normalizedMessages, (AssistantMessage) message);
                case TOOLRESULT -> appendMerged(normalizedMessages, normalizeToolResultMessage((ToolResultMessage) message));
            }
        }
        return List.copyOf(normalizedMessages);
    }

    private NormalizedUserMessage normalizeUserMessage(UserMessage userMessage) {
        String text = MessageContents.text(userMessage);
        if (text.isBlank()) {
            return null;
        }
        return new NormalizedUserMessage(List.of(new NormalizedTextContent(text)));
    }

    private void appendAssistantMessage(List<NormalizedRequestMessage> output, AssistantMessage assistantMessage) {
        List<NormalizedContent> contents = new ArrayList<>();
        for (MessageContent content : assistantMessage.messageContents()) {
            if (content instanceof TextContent textContent) {
                String text = textContent.getText() == null ? "" : textContent.getText().trim();
                if (!text.isBlank()) {
                    contents.add(new NormalizedTextContent(text));
                }
            } else if (content instanceof ThinkingContent thinkingContent) {
                String thinking = thinkingContent.getThinking() == null ? "" : thinkingContent.getThinking().trim();
                if (!thinking.isBlank()) {
                    contents.add(new NormalizedThinkingContent(thinking));
                }
            } else if (content instanceof ToolCallContent toolCallContent) {
                contents.add(new NormalizedToolCallContent(
                        toolCallContent.getToolCallId(),
                        toolCallContent.getToolName(),
                        resolveArgumentsJson(toolCallContent)
                ));
            }
        }

        if (!contents.isEmpty() || assistantMessage.getProviderState() != null) {
            output.add(new NormalizedAssistantMessage(contents, assistantMessage.getProviderState()));
        }
    }

    private NormalizedContextMessage normalizeToolResultMessage(ToolResultMessage toolResultMessage) {
        return new NormalizedContextMessage(List.of(new NormalizedToolResultContent(
                toolResultMessage.getToolCallId(),
                toolResultMessage.getToolName(),
                MessageContents.text(toolResultMessage),
                toolResultMessage.isError(),
                toolResultMessage.getDetails()
        )));
    }

    private void appendMerged(List<NormalizedRequestMessage> output, NormalizedRequestMessage nextMessage) {
        if (nextMessage == null || nextMessage.contents().isEmpty()) {
            return;
        }
        if (output.isEmpty()) {
            output.add(nextMessage);
            return;
        }

        NormalizedRequestMessage previousMessage = output.getLast();
        if (previousMessage.kind() == NormalizedRequestMessage.Kind.USER
                && nextMessage.kind() == NormalizedRequestMessage.Kind.USER
                && previousMessage instanceof NormalizedUserMessage previousUser
                && nextMessage instanceof NormalizedUserMessage nextUser) {
            output.set(output.size() - 1, previousUser.withContents(merged(previousUser.contents(), nextUser.contents())));
            return;
        }
        if (previousMessage.kind() == NormalizedRequestMessage.Kind.CONTEXT
                && nextMessage.kind() == NormalizedRequestMessage.Kind.CONTEXT
                && previousMessage instanceof NormalizedContextMessage previousContext
                && nextMessage instanceof NormalizedContextMessage nextContext) {
            output.set(output.size() - 1, previousContext.withContents(merged(previousContext.contents(), nextContext.contents())));
            return;
        }

        output.add(nextMessage);
    }

    private List<NormalizedContent> merged(List<NormalizedContent> first, List<NormalizedContent> second) {
        List<NormalizedContent> merged = new ArrayList<>(first);
        merged.addAll(second);
        return merged;
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
}
