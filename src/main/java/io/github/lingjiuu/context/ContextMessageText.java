package io.github.lingjiuu.context;

import io.github.lingjiuu.skill.Skill;
import io.github.lingjiuu.tool.ToolDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ContextMessageText {

    private ContextMessageText() {
    }

    static String toolInstructions(List<ToolDefinition> activeTools) {
        List<ToolDefinition> tools = activeTools == null ? List.of() : activeTools;
        List<String> toolLines = new ArrayList<>();
        Set<String> guidelines = new LinkedHashSet<>();
        for (ToolDefinition tool : tools) {
            if (tool == null) {
                continue;
            }
            String snippet = normalizeOneLine(tool.promptSnippet());
            if (snippet != null) {
                toolLines.add("- " + tool.name() + ": " + snippet);
            }
            List<String> promptGuidelines = tool.promptGuidelines() == null ? List.of() : tool.promptGuidelines();
            for (String guideline : promptGuidelines) {
                String normalized = normalizeOneLine(guideline);
                if (normalized != null) {
                    guidelines.add(normalized);
                }
            }
        }
        addBuiltInToolGuidelines(tools, guidelines);

        StringBuilder text = new StringBuilder();
        if (!toolLines.isEmpty()) {
            text.append("Available tools:\n").append(String.join("\n", toolLines));
        }
        if (!guidelines.isEmpty()) {
            appendSection(text, "Tool guidelines:\n" + guidelineText(guidelines));
        }
        return text.toString();
    }

    static String userInstructions(Path directory, String instructions) {
        return "# AGENTS.md instructions for " + normalizePath(directory)
                + "\n\n<INSTRUCTIONS>\n"
                + (instructions == null ? "" : instructions.trim())
                + "\n</INSTRUCTIONS>";
    }

    static String availableSkills(List<Skill> skills) {
        List<Skill> visibleSkills = skills == null ? List.of() : skills.stream()
                .filter(skill -> skill != null
                        && !skill.isDisableModelInvocation()
                        && normalizeOneLine(skill.getName()) != null
                        && normalizeOneLine(skill.getDescription()) != null
                        && skill.getLocation() != null)
                .toList();

        StringBuilder text = new StringBuilder();
        text.append("## Skills\n");
        text.append("A skill is a set of local instructions to follow that is stored in a `SKILL.md` file. ");
        text.append("Below is the list of skills available in this session.\n\n");
        text.append("### Available skills\n");
        for (Skill skill : visibleSkills) {
            text.append("- ")
                    .append(normalizeOneLine(skill.getName()))
                    .append(": ")
                    .append(normalizeOneLine(skill.getDescription()))
                    .append(" (file: ")
                    .append(normalizePath(skill.getLocation()))
                    .append(")\n");
        }
        text.append("\n### How to use skills\n");
        text.append("- If the user names a skill with `$SkillName`, selects one explicitly, or the task clearly matches a skill description, use that skill for this turn.\n");
        text.append("- Skill bodies are injected into the conversation when explicitly selected or mentioned; otherwise open the listed `SKILL.md` with the read tool before following it.\n");
        text.append("- When a skill references relative paths, resolve them relative to the directory containing its `SKILL.md`.");
        return text.toString();
    }

    private static void addBuiltInToolGuidelines(List<ToolDefinition> activeTools, Set<String> guidelines) {
        Set<String> names = new LinkedHashSet<>();
        for (ToolDefinition tool : activeTools) {
            if (tool != null && tool.name() != null) {
                names.add(tool.name());
            }
        }
        boolean hasBash = names.contains("bash");
        boolean hasExplorer = names.contains("grep") || names.contains("find") || names.contains("ls");
        if (hasBash && hasExplorer) {
            guidelines.add("Prefer grep/find/ls tools over bash for file exploration.");
        } else if (hasBash) {
            guidelines.add("Use bash for shell commands and file operations.");
        }
    }

    private static String guidelineText(Set<String> guidelines) {
        StringBuilder text = new StringBuilder();
        for (String guideline : guidelines) {
            text.append("- ").append(guideline).append('\n');
        }
        trimTrailingNewlines(text);
        return text.toString();
    }

    private static void appendSection(StringBuilder builder, String section) {
        if (section == null || section.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(section.trim());
    }

    private static String normalizeOneLine(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizePath(Path path) {
        return path == null ? "" : path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static void trimTrailingNewlines(StringBuilder builder) {
        while (!builder.isEmpty() && builder.charAt(builder.length() - 1) == '\n') {
            builder.deleteCharAt(builder.length() - 1);
        }
    }
}
