package io.github.lingjiuu.context;

import io.github.lingjiuu.skill.Skill;

import java.nio.file.Path;
import java.util.List;

final class ContextMessageText {

    private ContextMessageText() {
    }

    static String userInstructions(Path directory, String instructions) {
        return "# AGENTS.md instructions for " + normalizePath(directory)
                + "\n\n<INSTRUCTIONS>\n"
                + (instructions == null ? "" : instructions.trim())
                + "\n</INSTRUCTIONS>";
    }

    static String availableSkills(List<Skill> skills) {
        List<Skill> visibleSkills = skills == null ? List.of() : skills.stream()
                .filter(skill -> skill != null && skill.isModelVisible())
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
}
