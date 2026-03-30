package io.github.lingjiuu.message;

import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;

import java.util.ArrayList;
import java.util.List;

public final class MessageContents {

    private MessageContents() {
    }

    public static String text(Message message) {
        StringBuilder text = new StringBuilder();
        for (MessageContent content : message.messageContents()) {
            if (content instanceof TextContent textContent
                    && textContent.getText() != null
                    && !textContent.getText().isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textContent.getText());
            }
        }
        return text.toString().trim();
    }

    public static String thinking(AssistantMessage message) {
        StringBuilder thinking = new StringBuilder();
        for (MessageContent content : message.messageContents()) {
            if (content instanceof ThinkingContent thinkingContent
                    && thinkingContent.getThinking() != null
                    && !thinkingContent.getThinking().isBlank()) {
                if (!thinking.isEmpty()) {
                    thinking.append('\n');
                }
                thinking.append(thinkingContent.getThinking());
            }
        }
        return thinking.toString().trim();
    }

    public static List<ToolCallContent> toolCalls(AssistantMessage message) {
        List<ToolCallContent> toolCalls = new ArrayList<>();
        for (MessageContent content : message.messageContents()) {
            if (content instanceof ToolCallContent toolCallContent) {
                toolCalls.add(toolCallContent);
            }
        }
        return toolCalls;
    }
}
