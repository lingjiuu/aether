package io.github.lingjiuu.tool.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ReadFileState {

    private final Map<Path, Snapshot> snapshots = new ConcurrentHashMap<>();

    public void recordFull(Path path, String content, FileTime modifiedAt) {
        Path normalized = normalize(path);
        snapshots.put(normalized, new Snapshot(normalized, content == null ? "" : content, modifiedAt, false));
    }

    public void recordPartial(Path path, FileTime modifiedAt) {
        Path normalized = normalize(path);
        Snapshot existing = snapshots.get(normalized);
        if (existing != null && !existing.partial() && existing.sameModifiedAt(modifiedAt)) {
            return;
        }
        snapshots.put(normalized, new Snapshot(normalized, null, modifiedAt, true));
    }

    public Snapshot get(Path path) {
        if (path == null) {
            return null;
        }
        return snapshots.get(normalize(path));
    }

    public void clear() {
        snapshots.clear();
    }

    private Path normalize(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.exists(normalized)) {
            try {
                return normalized.toRealPath();
            } catch (IOException ignored) {
            }
        }
        return normalized;
    }

    public record Snapshot(
            Path path,
            String content,
            FileTime modifiedAt,
            boolean partial
    ) {
        public boolean matchesCurrent(String currentContent, FileTime currentModifiedAt) {
            if (partial) {
                return sameModifiedAt(currentModifiedAt);
            }
            return Objects.equals(content, currentContent == null ? "" : currentContent);
        }

        public boolean sameModifiedAt(FileTime currentModifiedAt) {
            return Objects.equals(modifiedAt, currentModifiedAt);
        }
    }
}
