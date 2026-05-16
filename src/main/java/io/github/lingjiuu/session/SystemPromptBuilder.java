package io.github.lingjiuu.session;

import io.github.lingjiuu.resource.ContextFile;
import io.github.lingjiuu.resource.Skill;
import io.github.lingjiuu.tool.ToolDefinition;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SystemPromptBuilder {

    public String build(String basePrompt, List<ToolDefinition> activeTools) {
        return build(SystemPromptBuildOptions.builder()
                .customPrompt(basePrompt)
                .activeTools(activeTools == null ? List.of() : activeTools)
                .build());
    }

    public String build(SystemPromptBuildOptions options) {
        SystemPromptBuildOptions safeOptions = options == null
                ? SystemPromptBuildOptions.builder().build()
                : options;
        String prompt = safeOptions.getCustomPrompt() == null ? "" : safeOptions.getCustomPrompt().trim();
        String appendPrompt = safeOptions.getAppendPrompt() == null ? "" : safeOptions.getAppendPrompt().trim();
        List<ToolDefinition> safeTools = safeOptions.getActiveTools() == null
                ? List.of()
                : safeOptions.getActiveTools();

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

        addBuiltInGuidelines(safeTools, guidelines);

        StringBuilder builder = new StringBuilder(prompt);
        if (!appendPrompt.isBlank()) {
            appendSectionBreak(builder);
            builder.append(appendPrompt);
        }
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
        appendContextFiles(builder, safeOptions.getContextFiles());
        appendSkills(builder, safeOptions.getSkills());
        appendDateAndCwd(builder, safeOptions.getCurrentDate(), safeOptions.getCwd());

        return builder.toString();
    }

    private void addBuiltInGuidelines(List<ToolDefinition> activeTools, Set<String> guidelines) {
        Set<String> names = new LinkedHashSet<>();
        for (ToolDefinition tool : activeTools) {
            if (tool != null && tool.name() != null) {
                names.add(tool.name());
            }
        }
        boolean hasBash = names.contains("bash");
        boolean hasExplorer = names.contains("grep") || names.contains("find") || names.contains("ls");
        if (hasBash && hasExplorer) {
            guidelines.add("Prefer grep/find/ls tools over bash for file exploration (faster, respects .gitignore)");
        } else if (hasBash) {
            guidelines.add("Use bash for file operations like ls, rg, find");
        }
    }

    private void appendContextFiles(StringBuilder builder, List<ContextFile> contextFiles) {
        List<ContextFile> safeContextFiles = contextFiles == null ? List.of() : contextFiles;
        List<ContextFile> visibleContextFiles = safeContextFiles.stream()
                .filter(contextFile -> contextFile != null
                        && contextFile.getPath() != null
                        && contextFile.getContent() != null
                        && !contextFile.getContent().isBlank())
                .toList();
        if (visibleContextFiles.isEmpty()) {
            return;
        }

        appendSectionBreak(builder);
        builder.append("# Project Context\n\n");
        builder.append("Project-specific instructions and guidelines:\n\n");
        for (ContextFile contextFile : visibleContextFiles) {
            builder.append("## ").append(normalizePath(contextFile.getPath())).append("\n\n");
            builder.append(contextFile.getContent().trim()).append("\n\n");
        }
        trimTrailingNewline(builder);
    }

    private void appendSkills(StringBuilder builder, List<Skill> skills) {
        List<Skill> safeSkills = skills == null ? List.of() : skills;
        List<Skill> visibleSkills = safeSkills.stream()
                .filter(skill -> skill != null
                        && !skill.isDisableModelInvocation()
                        && normalizeOneLine(skill.getName()) != null
                        && normalizeOneLine(skill.getDescription()) != null
                        && skill.getLocation() != null)
                .toList();
        if (visibleSkills.isEmpty()) {
            return;
        }

        appendSectionBreak(builder);
        builder.append("The following skills provide specialized instructions for specific tasks.\n");
        builder.append("Use the read tool to load a skill's file when the task matches its description.\n");
        builder.append("When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.\n\n");
        builder.append("<available_skills>\n");
        for (Skill skill : visibleSkills) {
            builder.append("  <skill>\n");
            builder.append("    <name>").append(escapeXml(normalizeOneLine(skill.getName()))).append("</name>\n");
            builder.append("    <description>").append(escapeXml(normalizeOneLine(skill.getDescription()))).append("</description>\n");
            builder.append("    <location>").append(escapeXml(normalizePath(skill.getLocation()))).append("</location>\n");
            builder.append("  </skill>\n");
        }
        builder.append("</available_skills>");
    }

    private void appendDateAndCwd(StringBuilder builder, LocalDate currentDate, Path cwd) {
        if (currentDate == null && cwd == null) {
            return;
        }
        if (currentDate != null) {
            appendSectionBreak(builder);
            builder.append("Current date: ").append(currentDate);
        }
        if (cwd != null) {
            if (currentDate == null) {
                appendSectionBreak(builder);
            } else {
                builder.append('\n');
            }
            builder.append("Current working directory: ").append(normalizePath(cwd));
        }
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
