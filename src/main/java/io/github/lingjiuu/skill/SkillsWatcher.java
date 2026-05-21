package io.github.lingjiuu.skill;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

public class SkillsWatcher implements AutoCloseable {

    private static final long DEBOUNCE_MILLIS = 300;

    private final SkillsManager skillsManager;
    private final WatchService watchService;
    private final IntConsumer onReload;
    private final Map<WatchKey, Path> watchedDirectories = new HashMap<>();
    private volatile boolean running = true;
    private Thread thread;

    public SkillsWatcher(SkillsManager skillsManager, IntConsumer onReload) {
        if (skillsManager == null) {
            throw new IllegalArgumentException("skillsManager must not be null");
        }
        this.skillsManager = skillsManager;
        this.onReload = onReload == null ? ignored -> {
        } : onReload;
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new SkillException("Failed to create skills watcher.", e);
        }
    }

    public void start() {
        if (thread != null) {
            return;
        }
        registerWatchedPaths();
        thread = Thread.ofVirtual()
                .name("aether-skills-watcher")
                .start(this::runLoop);
    }

    private void runLoop() {
        boolean dirty = false;
        long deadline = 0L;
        while (running) {
            WatchKey key;
            try {
                key = watchService.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                return;
            }

            if (key != null) {
                dirty = processKey(key) || dirty;
                deadline = System.currentTimeMillis() + DEBOUNCE_MILLIS;
            }

            if (dirty && System.currentTimeMillis() >= deadline) {
                reloadAndRewatch();
                dirty = false;
            }
        }
    }

    private boolean processKey(WatchKey key) {
        boolean dirty = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                dirty = true;
                continue;
            }
            Path directory = watchedDirectories.get(key);
            Object context = event.context();
            if (directory != null && context instanceof Path relativePath) {
                Path changed = directory.resolve(relativePath);
                if (isRelevantChange(changed)) {
                    dirty = true;
                }
            } else {
                dirty = true;
            }
        }
        if (!key.reset()) {
            watchedDirectories.remove(key);
        }
        return dirty;
    }

    private boolean isRelevantChange(Path path) {
        if (path == null) {
            return false;
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        if ("SKILL.md".equals(fileName) || "skills".equals(fileName) || ".aether".equals(fileName) || ".agent".equals(fileName)) {
            return true;
        }
        for (Path root : skillsManager.skillRoots()) {
            if (path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
                return true;
            }
        }
        return Files.isDirectory(path);
    }

    private void reloadAndRewatch() {
        int availableCount = skillsManager.reload();
        registerWatchedPaths();
        onReload.accept(availableCount);
    }

    private synchronized void registerWatchedPaths() {
        watchedDirectories.keySet().forEach(WatchKey::cancel);
        watchedDirectories.clear();
        for (Path root : skillsManager.watchRoots()) {
            Path normalized = root.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized)) {
                continue;
            }
            if (skillsManager.skillRoots().stream().anyMatch(skillRoot ->
                    normalized.equals(skillRoot.toAbsolutePath().normalize()))) {
                registerTree(normalized);
            } else {
                registerDirectory(normalized);
            }
        }
    }

    private void registerTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isDirectory)
                    .forEach(this::registerDirectory);
        } catch (IOException ignored) {
        }
    }

    private void registerDirectory(Path directory) {
        try {
            WatchKey key = directory.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
            );
            watchedDirectories.put(key, directory);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
        if (thread != null) {
            thread.interrupt();
        }
    }
}
