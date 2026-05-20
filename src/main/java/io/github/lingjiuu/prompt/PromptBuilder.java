package io.github.lingjiuu.prompt;

import io.github.lingjiuu.llm.LlmCallOptions;
import io.github.lingjiuu.resource.ContextFile;
import io.github.lingjiuu.resource.PromptResources;
import io.github.lingjiuu.resource.Skill;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.tool.ToolDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PromptBuilder {

    public Prompt build(PromptBuildInput input) {
        if (input == null || input.config() == null || input.contextProjection() == null) {
            throw new PromptBuildException("Prompt build input is incomplete.");
        }
        SessionConfig config = input.config();
        return new Prompt(
                buildSystemPrompt(config, input.tools()),
                config.model(),
                input.tools(),
                input.contextProjection().messages(),
                LlmCallOptions.builder()
                        .reasoning(config.reasoning())
                        .build()
        );
    }

    private String buildSystemPrompt(SessionConfig config, List<ToolDefinition> activeTools) {
        PromptResources resources = config.promptResources();
        String basePrompt = trimToEmpty(config.systemPrompt());
        String appendPrompt = resources == null ? "" : trimToEmpty(resources.getAppendSystemPrompt());
        List<ToolDefinition> tools = activeTools == null ? List.of() : activeTools;

        StringBuilder builder = new StringBuilder(basePrompt);
        appendSection(builder, appendPrompt);
        appendToolInstructions(builder, tools);
        if (resources != null) {
            appendContextFiles(builder, resources.getContextFiles());
            appendSkills(builder, resources.getSkills());
        }
        return builder.toString();
    }

    private void appendToolInstructions(StringBuilder builder, List<ToolDefinition> activeTools) {
        List<String> toolLines = new ArrayList<>();
        Set<String> guidelines = new LinkedHashSet<>();
        for (ToolDefinition tool : activeTools) {
            if (tool == null) {
                continue;
            }
            String snippet = normalizeOneLine(tool.promptSnippet());
            if (snippet != null) {
                toolLines.add("- " + tool.name() + ": " + snippet);
            }
            for (String guideline : tool.promptGuidelines()) {
                String normalized = normalizeOneLine(guideline);
                if (normalized != null) {
                    guidelines.add(normalized);
                }
            }
        }
        addBuiltInToolGuidelines(activeTools, guidelines);

        if (!toolLines.isEmpty()) {
            appendSection(builder, "Available tools:\n" + String.join("\n", toolLines));
        }
        if (!guidelines.isEmpty()) {
            StringBuilder guidelineText = new StringBuilder("Tool guidelines:\n");
            for (String guideline : guidelines) {
                guidelineText.append("- ").append(guideline).append('\n');
            }
            trimTrailingNewline(guidelineText);
            appendSection(builder, guidelineText.toString());
        }
    }

    private void addBuiltInToolGuidelines(List<ToolDefinition> activeTools, Set<String> guidelines) {
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

    private void appendContextFiles(StringBuilder builder, List<ContextFile> contextFiles) {
        List<ContextFile> visibleContextFiles = contextFiles == null ? List.of() : contextFiles.stream()
                .filter(contextFile -> contextFile != null
                        && contextFile.getPath() != null
                        && contextFile.getContent() != null
                        && !contextFile.getContent().isBlank())
                .toList();
        if (visibleContextFiles.isEmpty()) {
            return;
        }

        StringBuilder section = new StringBuilder("# Project Context\n\n");
        section.append("Project-specific instructions and guidelines:\n\n");
        for (ContextFile contextFile : visibleContextFiles) {
            section.append("## ").append(normalizePath(contextFile.getPath())).append("\n\n");
            section.append(contextFile.getContent().trim()).append("\n\n");
        }
        trimTrailingNewline(section);
        trimTrailingNewline(section);
        appendSection(builder, section.toString());
    }

    private void appendSkills(StringBuilder builder, List<Skill> skills) {
        List<Skill> visibleSkills = skills == null ? List.of() : skills.stream()
                .filter(skill -> skill != null
                        && !skill.isDisableModelInvocation()
                        && normalizeOneLine(skill.getName()) != null
                        && normalizeOneLine(skill.getDescription()) != null
                        && skill.getLocation() != null)
                .toList();
        if (visibleSkills.isEmpty()) {
            return;
        }

        StringBuilder section = new StringBuilder();
        section.append("The following skills provide specialized instructions for specific tasks.\n");
        section.append("Use the read tool to load a skill's file when the task matches its description.\n");
        section.append("When a skill file references a relative path, resolve it against the skill directory.\n\n");
        section.append("<available_skills>\n");
        for (Skill skill : visibleSkills) {
            section.append("  <skill>\n");
            section.append("    <name>").append(escapeXml(normalizeOneLine(skill.getName()))).append("</name>\n");
            section.append("    <description>").append(escapeXml(normalizeOneLine(skill.getDescription()))).append("</description>\n");
            section.append("    <location>").append(escapeXml(normalizePath(skill.getLocation()))).append("</location>\n");
            section.append("  </skill>\n");
        }
        section.append("</available_skills>");
        appendSection(builder, section.toString());
    }

    private void appendSection(StringBuilder builder, String section) {
        if (section == null || section.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(section.trim());
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
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

    private String normalizePath(Path path) {
        return path == null ? "" : path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private void trimTrailingNewline(StringBuilder builder) {
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == '\n') {
            builder.deleteCharAt(length - 1);
        }
    }
}
