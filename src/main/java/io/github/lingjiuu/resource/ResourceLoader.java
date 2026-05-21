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

    private Path canonicalKey(Path path) throws IOException {
        return path.toRealPath().toAbsolutePath().normalize();
    }
}
