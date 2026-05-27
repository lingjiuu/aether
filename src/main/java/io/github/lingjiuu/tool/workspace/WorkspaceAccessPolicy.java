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

    public Path resolvePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }

        Path requested = Path.of(path);
        return requested.isAbsolute()
                ? requested.toAbsolutePath().normalize()
                : root.resolve(requested).toAbsolutePath().normalize();
    }

    public Path resolveReadablePath(String path) {
        return normalizeForAccess(resolvePath(path));
    }

    public Path resolveWritablePath(String path) {
        return normalizeForAccess(resolvePath(path));
    }

    public boolean isInsideWorkspace(String path) {
        try {
            return isInsideWorkspace(resolvePath(path));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean isInsideWorkspace(Path path) {
        if (path == null) {
            return false;
        }
        return normalizeForAccess(path.toAbsolutePath().normalize()).startsWith(root);
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

    private Path normalizeForAccess(Path path) {
        if (Files.exists(path)) {
            return normalizeExistingPath(path);
        }

        Path parent = path.getParent();
        Path nearestExistingParent = nearestExistingParent(parent);
        Path checkedParent = normalizeExistingPath(nearestExistingParent);
        try {
            if (nearestExistingParent != null && path.startsWith(nearestExistingParent)) {
                return checkedParent.resolve(nearestExistingParent.relativize(path)).normalize();
            }
        } catch (IllegalArgumentException ignored) {
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
