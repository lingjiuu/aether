package io.github.lingjiuu.tool.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.transcript.TranscriptStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

public class ToolArtifactStore {

    private static final Pattern SAFE_NAME = Pattern.compile("[^A-Za-z0-9._-]");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path toolResultsDir;

    public ToolArtifactStore(Path toolResultsDir) {
        if (toolResultsDir == null) {
            throw new IllegalArgumentException("toolResultsDir must not be null");
        }
        this.toolResultsDir = toolResultsDir.toAbsolutePath().normalize();
    }

    public static ToolArtifactStore forSession(TranscriptStore transcriptStore, String sessionId) {
        String safeSessionId = safeSegment(sessionId, "session");
        Path baseDir = transcriptStore == null
                ? Path.of(System.getProperty("java.io.tmpdir"), "aether-tool-results")
                : transcriptStore.transcriptsDir();
        return new ToolArtifactStore(baseDir.resolve(safeSessionId).resolve("tool-results"));
    }

    public Path toolResultsDir() {
        return toolResultsDir;
    }

    public PersistedToolOutput persistText(
            String toolCallId,
            String suffix,
            String content,
            ToolResultPreviewMode previewMode
    ) {
        String safeContent = content == null ? "" : content;
        Path path = pathFor(toolCallId, suffix, "txt");
        try {
            Files.createDirectories(toolResultsDir);
            try {
                Files.writeString(
                        path,
                        safeContent,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
            }
            return persisted(path, false, previewMode);
        } catch (IOException e) {
            throw new ToolArtifactStoreException("Failed to persist tool text output.", e);
        }
    }

    public PersistedToolOutput persistTextFile(
            String toolCallId,
            String suffix,
            Path source,
            ToolResultPreviewMode previewMode
    ) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        Path path = pathFor(toolCallId, suffix, "txt");
        try {
            Files.createDirectories(toolResultsDir);
            try {
                Files.copy(source, path);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
            }
            return persisted(path, false, previewMode);
        } catch (IOException e) {
            throw new ToolArtifactStoreException("Failed to persist tool text output file.", e);
        }
    }

    public PersistedToolOutput persistJson(
            String toolCallId,
            String suffix,
            Object value,
            ToolResultPreviewMode previewMode
    ) {
        Path path = pathFor(toolCallId, suffix, "json");
        try {
            Files.createDirectories(toolResultsDir);
            try {
                Files.writeString(
                        path,
                        objectMapper.writeValueAsString(value),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
            }
            return persisted(path, true, previewMode);
        } catch (IOException e) {
            throw new ToolArtifactStoreException("Failed to persist tool JSON output.", e);
        }
    }

    private PersistedToolOutput persisted(Path path, boolean json, ToolResultPreviewMode previewMode) throws IOException {
        long size = Files.size(path);
        Preview preview = preview(path, previewMode);
        return new PersistedToolOutput(path, size, preview.text(), preview.hasMore(), json);
    }

    private Preview preview(Path path, ToolResultPreviewMode previewMode) throws IOException {
        long size = Files.size(path);
        if (size <= 0) {
            return new Preview("", false);
        }
        int maxBytes = ToolResultLimits.PREVIEW_SIZE_BYTES;
        int bytesToRead = (int) Math.min(size, maxBytes);
        byte[] bytes = new byte[bytesToRead];
        if (previewMode == ToolResultPreviewMode.TAIL && size > maxBytes) {
            try (InputStream input = Files.newInputStream(path)) {
                input.skipNBytes(size - bytesToRead);
                readFully(input, bytes);
            }
            return new Preview(trimToValidUtf8(bytes, true), true);
        }
        try (InputStream input = Files.newInputStream(path)) {
            readFully(input, bytes);
        }
        return new Preview(trimToValidUtf8(bytes, false), size > maxBytes);
    }

    private void readFully(InputStream input, byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int read = input.read(bytes, offset, bytes.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
    }

    private String trimToValidUtf8(byte[] bytes, boolean fromTail) {
        int start = 0;
        int end = bytes.length;
        if (fromTail) {
            while (start < end && (bytes[start] & 0xc0) == 0x80) {
                start++;
            }
        } else {
            while (end > start && (bytes[end - 1] & 0xc0) == 0x80) {
                end--;
            }
        }
        return new String(bytes, start, Math.max(0, end - start), StandardCharsets.UTF_8);
    }

    private Path pathFor(String toolCallId, String suffix, String extension) {
        String safeCallId = safeSegment(toolCallId, "tool-call");
        String safeSuffix = suffix == null || suffix.isBlank() ? "" : "-" + safeSegment(suffix, "artifact");
        String safeExtension = safeSegment(extension, "txt");
        return toolResultsDir.resolve(safeCallId + safeSuffix + "." + safeExtension);
    }

    private static String safeSegment(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return SAFE_NAME.matcher(normalized).replaceAll("_");
    }

    private record Preview(String text, boolean hasMore) {
    }

    public static class ToolArtifactStoreException extends RuntimeException {
        public ToolArtifactStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
