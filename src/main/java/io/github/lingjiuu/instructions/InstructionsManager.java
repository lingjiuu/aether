package io.github.lingjiuu.instructions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InstructionsManager {

    public static final String DEFAULT_AGENTS_MD_FILENAME = "AGENTS.md";
    public static final String LOCAL_AGENTS_MD_FILENAME = "AGENTS.override.md";

    private static final String AGENTS_MD_SEPARATOR = "\n\n--- project-doc ---\n\n";
    private static final List<String> PROJECT_ROOT_MARKERS = List.of(".git");
    private static final List<String> PROJECT_INSTRUCTION_DIR_NAMES = List.of(".aether", ".agent");

    private final Path cwd;
    private final Path agentDir;

    public InstructionsManager(Path cwd, Path agentDir) {
        if (cwd == null) {
            throw new IllegalArgumentException("cwd must not be null");
        }
        this.cwd = cwd.toAbsolutePath().normalize();
        this.agentDir = agentDir == null ? null : agentDir.toAbsolutePath().normalize();
    }

    public String baseInstructions() {
        String configured = readInstructionFile("SYSTEM.md");
        return configured == null || configured.isBlank()
                ? BaseInstructions.DEFAULT
                : configured.trim();
    }

    public String developerInstructions() {
        return readInstructionFile("APPEND_SYSTEM.md");
    }

    public AgentsMdInstructions loadAgentsMdInstructions() {
        Map<Path, LoadedAgentsMd> filesByRealPath = new LinkedHashMap<>();
        loadGlobalAgentsMdInstructions().forEach(file -> addLoadedFile(filesByRealPath, file));
        for (Path directory : projectDirectoriesRootFirst()) {
            loadAgentsMdFromDirectory(directory).ifPresent(file -> addLoadedFile(filesByRealPath, file));
        }

        if (filesByRealPath.isEmpty()) {
            return AgentsMdInstructions.empty();
        }
        List<LoadedAgentsMd> files = List.copyOf(filesByRealPath.values());
        return new AgentsMdInstructions(
                joinInstructions(files),
                files.stream()
                        .map(LoadedAgentsMd::path)
                        .toList()
        );
    }

    private String readInstructionFile(String fileName) {
        for (Path candidate : instructionFileCandidates(fileName)) {
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8).trim();
                } catch (IOException ignored) {
                    return "";
                }
            }
        }
        return "";
    }

    private List<Path> instructionFileCandidates(String fileName) {
        List<Path> candidates = new ArrayList<>();
        for (String dirName : PROJECT_INSTRUCTION_DIR_NAMES) {
            candidates.add(cwd.resolve(dirName).resolve(fileName));
        }
        if (agentDir != null) {
            candidates.add(agentDir.resolve(fileName));
        }
        return List.copyOf(candidates);
    }

    private void addLoadedFile(Map<Path, LoadedAgentsMd> filesByRealPath, LoadedAgentsMd loaded) {
        try {
            filesByRealPath.putIfAbsent(loaded.path().toRealPath().toAbsolutePath().normalize(), loaded);
        } catch (IOException ignored) {
        }
    }

    private List<LoadedAgentsMd> loadGlobalAgentsMdInstructions() {
        if (agentDir == null) {
            return List.of();
        }
        return loadAgentsMdFromDirectory(agentDir)
                .map(List::of)
                .orElse(List.of());
    }

    private List<Path> projectDirectoriesRootFirst() {
        Path root = projectRoot();
        List<Path> directories = new ArrayList<>();
        Path current = cwd;
        while (current != null) {
            directories.add(current);
            if (current.equals(root)) {
                break;
            }
            current = current.getParent();
        }
        directories.sort(Comparator.comparingInt(Path::getNameCount));
        return List.copyOf(directories);
    }

    private Path projectRoot() {
        Path current = cwd;
        while (current != null) {
            for (String marker : PROJECT_ROOT_MARKERS) {
                if (Files.exists(current.resolve(marker))) {
                    return current;
                }
            }
            current = current.getParent();
        }
        return cwd;
    }

    private Optional<LoadedAgentsMd> loadAgentsMdFromDirectory(Path directory) {
        if (directory == null) {
            return Optional.empty();
        }
        for (String fileName : List.of(LOCAL_AGENTS_MD_FILENAME, DEFAULT_AGENTS_MD_FILENAME)) {
            Path candidate = directory.resolve(fileName);
            if (!Files.isRegularFile(candidate) || !Files.isReadable(candidate)) {
                continue;
            }
            try {
                String text = Files.readString(candidate, StandardCharsets.UTF_8).trim();
                if (!text.isBlank()) {
                    return Optional.of(new LoadedAgentsMd(
                            text,
                            candidate.toAbsolutePath().normalize()
                    ));
                }
            } catch (IOException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private String joinInstructions(List<LoadedAgentsMd> files) {
        return files.stream()
                .map(LoadedAgentsMd::text)
                .filter(text -> text != null && !text.isBlank())
                .reduce((left, right) -> left + AGENTS_MD_SEPARATOR + right)
                .orElse("");
    }

    private record LoadedAgentsMd(String text, Path path) {
    }
}
