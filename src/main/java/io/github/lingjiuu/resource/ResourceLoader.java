package io.github.lingjiuu.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ResourceLoader {

    private static final List<String> PROJECT_RESOURCE_DIR_NAMES = List.of(".aether", ".agent");

    private static final List<String> CONTEXT_FILE_NAMES = List.of(
            "AGENTS.md",
            "AGENTS.MD",
            "CLAUDE.md",
            "CLAUDE.MD"
    );

    private final Path cwd;
    private final Path agentDir;

    public ResourceLoader(Path cwd, Path agentDir) {
        if (cwd == null) {
            throw new IllegalArgumentException("cwd must not be null");
        }
        if (agentDir == null) {
            throw new IllegalArgumentException("agentDir must not be null");
        }
        this.cwd = cwd.toAbsolutePath().normalize();
        this.agentDir = agentDir.toAbsolutePath().normalize();
    }

    public PromptResources load() {
        return PromptResources.builder()
                .systemPrompt(readFirstExisting(resourceFileCandidates("SYSTEM.md")))
                .appendSystemPrompt(readFirstExisting(resourceFileCandidates("APPEND_SYSTEM.md")))
                .contextFiles(loadContextFiles())
                .skills(loadSkills())
                .build();
    }

    private List<Path> resourceFileCandidates(String fileName) {
        List<Path> candidates = new ArrayList<>();
        for (String dirName : PROJECT_RESOURCE_DIR_NAMES) {
            candidates.add(cwd.resolve(dirName).resolve(fileName));
        }
        candidates.add(agentDir.resolve(fileName));
        return candidates;
    }

    private String readFirstExisting(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private List<ContextFile> loadContextFiles() {
        Map<Path, ContextFile> filesByRealPath = new LinkedHashMap<>();
        addContextFileFromDir(filesByRealPath, agentDir);

        List<Path> ancestors = ancestorsRootFirst(cwd);
        for (Path ancestor : ancestors) {
            addContextFileFromDir(filesByRealPath, ancestor);
        }
        return List.copyOf(filesByRealPath.values());
    }

    private List<Path> ancestorsRootFirst(Path path) {
        List<Path> ancestors = new ArrayList<>();
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            ancestors.add(current);
            current = current.getParent();
        }
        ancestors.sort(Comparator.comparingInt(Path::getNameCount));
        return ancestors;
    }

    private void addContextFileFromDir(Map<Path, ContextFile> filesByRealPath, Path dir) {
        for (String fileName : CONTEXT_FILE_NAMES) {
            Path candidate = dir.resolve(fileName);
            if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) {
                continue;
            }
            try {
                Path key = canonicalKey(candidate);
                filesByRealPath.putIfAbsent(key, ContextFile.builder()
                        .path(candidate.toAbsolutePath().normalize())
                        .content(Files.readString(candidate, StandardCharsets.UTF_8))
                        .build());
            } catch (IOException ignored) {
            }
            return;
        }
    }

    private List<Skill> loadSkills() {
        Map<Path, Skill> skillsByPath = new LinkedHashMap<>();
        addSkillsFromDir(skillsByPath, agentDir.resolve("skills"));
        for (String dirName : PROJECT_RESOURCE_DIR_NAMES) {
            addSkillsFromDir(skillsByPath, cwd.resolve(dirName).resolve("skills"));
        }
        return List.copyOf(skillsByPath.values());
    }

    private void addSkillsFromDir(Map<Path, Skill> skillsByPath, Path skillsDir) {
        if (!Files.isDirectory(skillsDir)) {
            return;
        }
        try (var paths = Files.walk(skillsDir)) {
            paths.filter(path -> Files.isRegularFile(path) && "SKILL.md".equals(path.getFileName().toString()))
                    .sorted()
                    .forEach(path -> loadSkill(path).ifPresent(skill -> {
                        try {
                            skillsByPath.putIfAbsent(canonicalKey(path), skill);
                        } catch (IOException ignored) {
                        }
                    }));
        } catch (IOException ignored) {
        }
    }

    private java.util.Optional<Skill> loadSkill(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, String> frontmatter = parseFrontmatter(content);
            String name = blankToNull(frontmatter.get("name"));
            if (name == null && path.getParent() != null && path.getParent().getFileName() != null) {
                name = path.getParent().getFileName().toString();
            }
            String description = blankToNull(frontmatter.get("description"));
            if (name == null || description == null) {
                return java.util.Optional.empty();
            }
            boolean disabled = "true".equalsIgnoreCase(blankToNull(frontmatter.get("disable-model-invocation")));
            return java.util.Optional.of(Skill.builder()
                    .name(name)
                    .description(description)
                    .location(path.toAbsolutePath().normalize())
                    .disableModelInvocation(disabled)
                    .build());
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    private Map<String, String> parseFrontmatter(String content) {
        String normalized = content == null ? "" : content.replace("\r\n", "\n").replace("\r", "\n");
        if (!normalized.startsWith("---\n")) {
            return Map.of();
        }
        int endIndex = normalized.indexOf("\n---", 4);
        if (endIndex < 0) {
            return Map.of();
        }
        String yaml = normalized.substring(4, endIndex);
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : yaml.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = stripSimpleQuotes(line.substring(colon + 1).trim());
            if (!key.isBlank()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private String stripSimpleQuotes(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Path canonicalKey(Path path) throws IOException {
        return path.toRealPath().toAbsolutePath().normalize();
    }
}
