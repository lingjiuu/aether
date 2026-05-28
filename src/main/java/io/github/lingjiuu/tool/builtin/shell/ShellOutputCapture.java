package io.github.lingjiuu.tool.builtin.shell;

import io.github.lingjiuu.tool.builtin.ToolOutputLimits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ShellOutputCapture {

    private final StreamBuffer stdout;
    private final StreamBuffer stderr;
    private final StreamBuffer aggregate;

    public ShellOutputCapture(String tempFilePrefix) {
        this(tempFilePrefix, ToolOutputLimits.BASH_MAX_LINES, ToolOutputLimits.DEFAULT_MAX_BYTES);
    }

    public ShellOutputCapture(String tempFilePrefix, int maxLines, int maxBytes) {
        if (tempFilePrefix == null || tempFilePrefix.isBlank()) {
            throw new IllegalArgumentException("tempFilePrefix must not be blank");
        }
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines must be positive");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.stdout = new StreamBuffer(tempFilePrefix, "stdout", maxLines, maxBytes);
        this.stderr = new StreamBuffer(tempFilePrefix, "stderr", maxLines, maxBytes);
        this.aggregate = new StreamBuffer(tempFilePrefix, "combined", maxLines, maxBytes);
    }

    public synchronized void appendStdout(byte[] bytes, int length) {
        stdout.append(bytes, length);
        aggregate.append(bytes, length);
    }

    public synchronized void appendStderr(byte[] bytes, int length) {
        stderr.append(bytes, length);
        aggregate.append(bytes, length);
    }

    public synchronized Snapshot snapshot(boolean persistIfTruncated) throws IOException {
        StreamSnapshot stdoutSnapshot = stdout.snapshot(persistIfTruncated);
        StreamSnapshot stderrSnapshot = stderr.snapshot(persistIfTruncated);
        StreamSnapshot aggregateSnapshot = aggregate.snapshot(persistIfTruncated);
        return new Snapshot(
                stdoutSnapshot,
                stderrSnapshot,
                aggregateSnapshot
        );
    }

    public record Snapshot(
            StreamSnapshot stdout,
            StreamSnapshot stderr,
            StreamSnapshot aggregate
    ) {
        public String content() {
            return aggregate.content();
        }

        public boolean truncated() {
            return stdout.truncated() || stderr.truncated() || aggregate.truncated();
        }

        public Map<String, Object> truncationDetails() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("truncated", truncated());
            details.put("aggregate", aggregate.truncationDetails());
            details.put("stdout", stdout.truncationDetails());
            details.put("stderr", stderr.truncationDetails());
            return details;
        }
    }

    public record StreamSnapshot(
            String content,
            StreamTruncation truncation,
            Path fullOutputPath
    ) {
        public boolean truncated() {
            return truncation != null && truncation.truncated();
        }

        public Map<String, Object> truncationDetails() {
            if (truncation == null) {
                return Map.of();
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("truncated", truncation.truncated());
            details.put("truncatedBy", truncation.truncatedBy());
            details.put("totalLines", truncation.totalLines());
            details.put("totalBytes", truncation.totalBytes());
            details.put("outputLines", truncation.outputLines());
            details.put("outputBytes", truncation.outputBytes());
            details.put("firstLineExceedsLimit", truncation.firstLineExceedsLimit());
            details.put("lastLinePartial", truncation.lastLinePartial());
            details.put("maxLines", truncation.maxLines());
            details.put("maxBytes", truncation.maxBytes());
            return details;
        }
    }

    public record StreamTruncation(
            String content,
            boolean truncated,
            String truncatedBy,
            int totalLines,
            int totalBytes,
            int outputLines,
            int outputBytes,
            boolean firstLineExceedsLimit,
            boolean lastLinePartial,
            int maxLines,
            int maxBytes
    ) {
    }

    private static final class StreamBuffer {
        private final String tempFilePrefix;
        private final String streamName;
        private final StringBuilder retained = new StringBuilder();
        private final int maxLines;
        private final int maxBytes;
        private int totalBytes;
        private int totalLines = 1;
        private Path fullOutputPath;

        private StreamBuffer(String tempFilePrefix, String streamName, int maxLines, int maxBytes) {
            this.tempFilePrefix = tempFilePrefix;
            this.streamName = streamName;
            this.maxLines = maxLines;
            this.maxBytes = maxBytes;
        }

        synchronized void append(byte[] bytes, int length) {
            if (bytes == null || length <= 0) {
                return;
            }
            String chunk = new String(bytes, 0, length, StandardCharsets.UTF_8);
            if (chunk.isEmpty()) {
                return;
            }
            try {
                if (willOverflow(chunk)) {
                    ensureFullOutputFile();
                }
                if (fullOutputPath != null) {
                    Files.writeString(
                            fullOutputPath,
                            chunk,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND
                    );
                }
            } catch (IOException e) {
                throw new IllegalStateException("failed to capture " + streamName + " output", e);
            }

            retained.append(chunk);
            totalBytes += byteLength(chunk);
            totalLines += newlineCount(chunk);
            if (isTruncated()) {
                TailPreview tail = tailPreview(retained.toString(), maxLines, maxBytes);
                retained.setLength(0);
                retained.append(tail.content());
            }
        }

        synchronized StreamSnapshot snapshot(boolean persistIfTruncated) throws IOException {
            TailPreview tail = tailPreview(retained.toString(), maxLines, maxBytes);
            boolean truncated = isTruncated();
            if (persistIfTruncated && truncated && fullOutputPath == null) {
                ensureFullOutputFile();
            }
            StreamTruncation truncation = new StreamTruncation(
                    tail.content(),
                    truncated,
                    truncatedBy(truncated),
                    totalLines,
                    totalBytes,
                    tail.outputLines(),
                    tail.outputBytes(),
                    false,
                    tail.lastLinePartial(),
                    maxLines,
                    maxBytes
            );
            return new StreamSnapshot(
                    tail.content(),
                    truncation,
                    fullOutputPath
            );
        }

        private boolean willOverflow(String chunk) {
            return totalBytes + byteLength(chunk) > maxBytes
                    || totalLines + newlineCount(chunk) > maxLines;
        }

        private boolean isTruncated() {
            return totalBytes > maxBytes || totalLines > maxLines;
        }

        private String truncatedBy(boolean truncated) {
            if (!truncated) {
                return null;
            }
            if (totalBytes > maxBytes) {
                return "bytes";
            }
            return "lines";
        }

        private void ensureFullOutputFile() throws IOException {
            if (fullOutputPath != null) {
                return;
            }
            fullOutputPath = Files.createTempFile(tempFilePrefix + "-" + streamName + "-", ".log");
            Files.writeString(
                    fullOutputPath,
                    retained.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        }
    }

    private static TailPreview tailPreview(String content, int maxLines, int maxBytes) {
        String safeContent = content == null ? "" : content;
        List<String> lines = List.of(safeContent.split("\n", -1));
        List<String> output = new ArrayList<>();
        int outputBytes = 0;
        boolean lastLinePartial = false;

        for (int i = lines.size() - 1; i >= 0 && output.size() < maxLines; i--) {
            String line = lines.get(i);
            int lineBytes = byteLength(line) + (output.isEmpty() ? 0 : 1);
            if (outputBytes + lineBytes > maxBytes) {
                if (output.isEmpty()) {
                    String partial = tailByBytes(line, maxBytes);
                    output.addFirst(partial);
                    outputBytes = byteLength(partial);
                    lastLinePartial = true;
                }
                break;
            }
            output.addFirst(line);
            outputBytes += lineBytes;
        }

        String preview = String.join("\n", output);
        return new TailPreview(
                preview,
                output.isEmpty() ? 0 : output.size(),
                byteLength(preview),
                lastLinePartial
        );
    }

    private static int newlineCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String tailByBytes(String text, int maxBytes) {
        if (byteLength(text) <= maxBytes) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = text.length() - 1; i >= 0; i--) {
            builder.insert(0, text.charAt(i));
            if (byteLength(builder.toString()) > maxBytes) {
                builder.deleteCharAt(0);
                break;
            }
        }
        return builder.toString();
    }

    private record TailPreview(
            String content,
            int outputLines,
            int outputBytes,
            boolean lastLinePartial
    ) {
    }
}
