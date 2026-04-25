package io.github.lingjiuu.compact.toolbudget;

import java.util.LinkedHashMap;
import java.util.Map;

public class ToolResultBudgetState {

    private final Map<String, String> replacements = new LinkedHashMap<>();

    public ToolResultBudgetState(ToolResultReplacementIndex index) {
        if (index != null) {
            replacements.putAll(index.asMap());
        }
    }

    public String replacementFor(String toolCallId) {
        return replacements.get(toolCallId);
    }

    public void put(String toolCallId, String replacement) {
        if (toolCallId != null && replacement != null) {
            replacements.put(toolCallId, replacement);
        }
    }

    public Map<String, String> replacements() {
        return Map.copyOf(replacements);
    }
}
