package io.github.lingjiuu.compact.toolbudget;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ToolResultContentReplacer {

    public List<Message> replace(List<Message> messages, Map<String, String> replacements) {
        if (messages == null || messages.isEmpty() || replacements == null || replacements.isEmpty()) {
            return messages == null ? List.of() : List.copyOf(messages);
        }
        List<Message> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (message instanceof ToolResultMessage toolResultMessage
                    && replacements.containsKey(toolResultMessage.getToolCallId())) {
                result.add(replace(toolResultMessage, replacements.get(toolResultMessage.getToolCallId())));
            } else {
                result.add(message);
            }
        }
        return List.copyOf(result);
    }

    private ToolResultMessage replace(ToolResultMessage source, String replacement) {
        return ToolResultMessage.builder()
                .id(source.id())
                .timestamp(source.timestamp())
                .toolCallId(source.getToolCallId())
                .toolName(source.getToolName())
                .details(source.getDetails())
                .isError(source.isError())
                .contents(List.of(TextContent.builder()
                        .text(replacement == null ? "" : replacement)
                        .build()))
                .build();
    }
}
