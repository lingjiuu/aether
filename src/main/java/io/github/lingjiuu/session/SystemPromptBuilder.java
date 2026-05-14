package io.github.lingjiuu.session;

import io.github.lingjiuu.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SystemPromptBuilder {

    public String build(String basePrompt, List<ToolDefinition> activeTools) {
        String prompt = basePrompt == null ? "" : basePrompt.trim();
        List<ToolDefinition> safeTools = activeTools == null ? List.of() : activeTools;

        List<String> toolLines = new ArrayList<>();
        Set<String> guidelines = new LinkedHashSet<>();
        for (ToolDefinition tool : safeTools) {
            if (tool == null) {
                continue;
            }
            String snippet = normalizeOneLine(tool.promptSnippet());
            if (snippet != null) {
                toolLines.add("- " + tool.name() + ": " + snippet);
            }
            List<String> toolGuidelines = tool.promptGuidelines();
            if (toolGuidelines == null) {
                continue;
            }
            for (String guideline : toolGuidelines) {
                String normalized = normalizeOneLine(guideline);
                if (normalized != null) {
                    guidelines.add(normalized);
                }
            }
        }

        StringBuilder builder = new StringBuilder(prompt);
        if (!toolLines.isEmpty()) {
            appendSectionBreak(builder);
            builder.append("Available tools:\n");
            builder.append(String.join("\n", toolLines));
        }
        if (!guidelines.isEmpty()) {
            appendSectionBreak(builder);
            builder.append("Tool guidelines:\n");
            for (String guideline : guidelines) {
                builder.append("- ").append(guideline).append('\n');
            }
            trimTrailingNewline(builder);
        }

        return builder.toString();
    }

    private String normalizeOneLine(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void appendSectionBreak(StringBuilder builder) {
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
    }

    private void trimTrailingNewline(StringBuilder builder) {
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == '\n') {
            builder.deleteCharAt(length - 1);
        }
    }
}
