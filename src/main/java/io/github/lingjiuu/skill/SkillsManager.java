package io.github.lingjiuu.skill;

import io.github.lingjiuu.input.InputItem;
import io.github.lingjiuu.input.SkillInput;
import io.github.lingjiuu.input.TextInput;
import io.github.lingjiuu.input.TurnInput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SkillsManager {

    private static final List<String> PROJECT_RESOURCE_DIR_NAMES = List.of(".aether", ".agent");
    private static final Pattern SKILL_MENTION_PATTERN = Pattern.compile("\\$([A-Za-z0-9_.:-]+)");

    private final Path cwd;
    private final Path agentDir;
    private volatile List<Skill> skills;

    public SkillsManager(Path cwd, Path agentDir) {
        if (cwd == null) {
            throw new IllegalArgumentException("cwd must not be null");
        }
        if (agentDir == null) {
            throw new IllegalArgumentException("agentDir must not be null");
        }
        this.cwd = cwd.toAbsolutePath().normalize();
        this.agentDir = agentDir.toAbsolutePath().normalize();
        this.skills = loadSkills();
    }

    private SkillsManager(Path cwd, Path agentDir, List<Skill> skills) {
        this.cwd = cwd.toAbsolutePath().normalize();
        this.agentDir = agentDir.toAbsolutePath().normalize();
        this.skills = skills == null ? List.of() : List.copyOf(skills);
    }

    public static SkillsManager empty(Path cwd) {
        Path root = cwd == null ? Path.of(System.getProperty("user.dir")) : cwd;
        return new SkillsManager(root, root, List.of());
    }

    public List<Skill> availableSkills() {
        return skills.stream()
                .filter(Skill::isModelVisible)
                .toList();
    }

    public synchronized int reload() {
        skills = loadSkills();
        return availableSkills().size();
    }

    public List<Path> skillRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(agentDir.resolve("skills"));
        for (String dirName : PROJECT_RESOURCE_DIR_NAMES) {
            roots.add(cwd.resolve(dirName).resolve("skills"));
        }
        return List.copyOf(roots);
    }

    public List<Path> watchRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(agentDir);
        roots.add(agentDir.resolve("skills"));
        roots.add(cwd);
        for (String dirName : PROJECT_RESOURCE_DIR_NAMES) {
            Path projectDir = cwd.resolve(dirName);
            roots.add(projectDir);
            roots.add(projectDir.resolve("skills"));
        }
        return List.copyOf(roots);
    }

    public Optional<Skill> findByName(String name) {
        return findByName(name, availableSkills());
    }

    private Optional<Skill> findByName(String name, List<Skill> availableSkills) {
        String normalizedName = normalizeName(name);
        if (normalizedName == null) {
            return Optional.empty();
        }
        List<Skill> matches = visibleSkills(availableSkills).stream()
                .filter(skill -> normalizedName.equals(normalizeName(skill.getName())))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    public Optional<Skill> findByPath(Path path) {
        return findByPath(path, availableSkills());
    }

    private Optional<Skill> findByPath(Path path, List<Skill> availableSkills) {
        if (path == null) {
            return Optional.empty();
        }
        Path expected = canonicalKey(resolve(path));
        return visibleSkills(availableSkills).stream()
                .filter(skill -> expected.equals(canonicalKey(skill.getLocation())))
                .findFirst();
    }

    public List<SkillInjection> resolveSkillInjections(TurnInput input) {
        return resolveSkillInjections(input, availableSkills());
    }

    public List<SkillInjection> resolveSkillInjections(TurnInput input, List<Skill> availableSkills) {
        if (input == null) {
            return List.of();
        }

        List<Skill> visibleSkills = visibleSkills(availableSkills);
        List<Skill> selected = new ArrayList<>();
        Set<Path> seenPaths = new LinkedHashSet<>();
        for (InputItem item : input.items()) {
            if (item instanceof SkillInput skillInput) {
                selectSkill(skillInput, visibleSkills, selected, seenPaths);
            }
        }
        for (InputItem item : input.items()) {
            if (item instanceof TextInput textInput) {
                for (String name : mentionedSkillNames(textInput.text())) {
                    findByName(name, visibleSkills).ifPresent(skill -> addSkill(skill, selected, seenPaths));
                }
            }
        }

        List<SkillInjection> injections = new ArrayList<>();
        for (Skill skill : selected) {
            injections.add(readSkill(skill));
        }
        return List.copyOf(injections);
    }

    private void selectSkill(
            SkillInput input,
            List<Skill> visibleSkills,
            List<Skill> selected,
            Set<Path> seenPaths
    ) {
        Optional<Skill> byPath = findByPath(input.path(), visibleSkills);
        if (byPath.isPresent()) {
            addSkill(byPath.get(), selected, seenPaths);
            return;
        }
        findByName(input.name(), visibleSkills).ifPresent(skill -> addSkill(skill, selected, seenPaths));
    }

    private void addSkill(Skill skill, List<Skill> selected, Set<Path> seenPaths) {
        Path key = canonicalKey(skill.getLocation());
        if (seenPaths.add(key)) {
            selected.add(skill);
        }
    }

    private SkillInjection readSkill(Skill skill) {
        try {
            return new SkillInjection(
                    skill.getName(),
                    skill.getLocation(),
                    Files.readString(skill.getLocation(), StandardCharsets.UTF_8)
            );
        } catch (IOException e) {
            throw new SkillException("Failed to read skill: " + skill.getLocation(), e);
        }
    }

    private List<String> mentionedSkillNames(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = SKILL_MENTION_PATTERN.matcher(text);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    private List<Skill> visibleSkills(List<Skill> skills) {
        return skills == null ? List.of() : skills.stream()
                .filter(skill -> skill != null && skill.isModelVisible())
                .toList();
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
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> loadSkill(path).ifPresent(skill ->
                            skillsByPath.putIfAbsent(canonicalKey(path), skill)));
        } catch (IOException ignored) {
        }
    }

    private Optional<Skill> loadSkill(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, String> frontmatter = parseFrontmatter(content);
            String name = blankToNull(frontmatter.get("name"));
            if (name == null && path.getParent() != null && path.getParent().getFileName() != null) {
                name = path.getParent().getFileName().toString();
            }
            String description = blankToNull(frontmatter.get("description"));
            if (name == null || description == null) {
                return Optional.empty();
            }
            boolean disabled = "true".equalsIgnoreCase(blankToNull(frontmatter.get("disable-model-invocation")));
            return Optional.of(Skill.builder()
                    .name(name)
                    .description(description)
                    .location(path.toAbsolutePath().normalize())
                    .disableModelInvocation(disabled)
                    .build());
        } catch (IOException e) {
            return Optional.empty();
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

    private String normalizeName(String name) {
        return name == null || name.isBlank() ? null : name.trim().toLowerCase();
    }

    private Path resolve(Path path) {
        return path.isAbsolute() ? path.toAbsolutePath().normalize() : cwd.resolve(path).toAbsolutePath().normalize();
    }

    private Path canonicalKey(Path path) {
        try {
            return path.toRealPath().toAbsolutePath().normalize();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
