package io.github.lingjiuu.tool.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileAccessPolicy {

    private final Path root;

    private FileAccessPolicy(Path root) {
        this.root = normalizeRoot(root);
    }

    public static FileAccessPolicy rootedAt(Path root) {
        return new FileAccessPolicy(root);
    }

    public Path resolveReadablePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("read path must not be blank");
        }

        Path requested = Path.of(path);
        Path resolved = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : root.resolve(requested).toAbsolutePath().normalize();
        Path checked = normalizeExistingPath(resolved);
        if (!checked.startsWith(root)) {
            throw new IllegalArgumentException("Read path is outside the allowed root: " + path);
        }
        return checked;
    }

    public Path root() {
        return root;
    }

    private Path normalizeRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("file access root must not be null");
        }
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.exists(absolute)) {
            try {
                return absolute.toRealPath();
            } catch (IOException e) {
                return absolute;
            }
        }
        return absolute;
    }

    private Path normalizeExistingPath(Path path) {
        if (Files.exists(path)) {
            try {
                return path.toRealPath();
            } catch (IOException e) {
                return path;
            }
        }
        return path;
    }
}
