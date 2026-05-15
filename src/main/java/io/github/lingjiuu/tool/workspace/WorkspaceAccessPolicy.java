package io.github.lingjiuu.tool.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class WorkspaceAccessPolicy {

    private final Path root;

    private WorkspaceAccessPolicy(Path root) {
        this.root = normalizeRoot(root);
    }

    public static WorkspaceAccessPolicy rootedAt(Path root) {
        return new WorkspaceAccessPolicy(root);
    }

    public Path resolveReadablePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }

        Path requested = Path.of(path);
        Path resolved = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : root.resolve(requested).toAbsolutePath().normalize();
        Path checked = normalizeExistingPath(resolved);
        if (!checked.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside the allowed root: " + path);
        }
        return checked;
    }

    public Path resolveWritablePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }

        Path requested = Path.of(path);
        Path resolved = requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : root.resolve(requested).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside the allowed root: " + path);
        }
        if (Files.exists(resolved)) {
            Path checked = normalizeExistingPath(resolved);
            if (!checked.startsWith(root)) {
                throw new IllegalArgumentException("Path is outside the allowed root: " + path);
            }
            return checked;
        }

        Path parent = resolved.getParent();
        Path nearestExistingParent = nearestExistingParent(parent);
        Path checkedParent = normalizeExistingPath(nearestExistingParent);
        if (!checkedParent.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside the allowed root: " + path);
        }
        return resolved;
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

    private Path nearestExistingParent(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current == null ? root : current;
    }
}
