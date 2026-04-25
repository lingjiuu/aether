package io.github.lingjiuu.compact.toolbudget;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.SystemMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolResultReplacementIndex {

    private final Map<String, String> replacements;

    public ToolResultReplacementIndex(Map<String, String> replacements) {
        this.replacements = replacements == null ? Map.of() : Map.copyOf(replacements);
    }

    public static ToolResultReplacementIndex fromMessages(List<Message> messages) {
        Map<String, String> replacements = new LinkedHashMap<>();
        if (messages == null) {
            return new ToolResultReplacementIndex(replacements);
        }
        for (Message message : messages) {
            if (message instanceof SystemMessage systemMessage
                    && systemMessage.getSubtype() == SystemMessage.Subtype.CONTENT_REPLACEMENT) {
                for (SystemMessage.ToolResultReplacement replacement : systemMessage.getToolResultReplacements()) {
                    if (replacement.toolCallId() != null && replacement.replacement() != null) {
                        replacements.put(replacement.toolCallId(), replacement.replacement());
                    }
                }
            }
        }
        return new ToolResultReplacementIndex(replacements);
    }

    public String replacementFor(String toolCallId) {
        return replacements.get(toolCallId);
    }

    public boolean contains(String toolCallId) {
        return replacements.containsKey(toolCallId);
    }

    public Map<String, String> asMap() {
        return replacements;
    }
}
