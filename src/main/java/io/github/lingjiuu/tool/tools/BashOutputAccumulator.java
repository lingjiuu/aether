package io.github.lingjiuu.tool.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class BashOutputAccumulator {

    private final StreamBuffer stdout;
    private final StreamBuffer stderr;
    private final StreamBuffer aggregate;

    BashOutputAccumulator() {
        this(ToolOutputLimits.BASH_MAX_LINES, ToolOutputLimits.DEFAULT_MAX_BYTES);
    }

    BashOutputAccumulator(int maxLines, int maxBytes) {
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines must be positive");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.stdout = new StreamBuffer("stdout", maxLines, maxBytes);
        this.stderr = new StreamBuffer("stderr", maxLines, maxBytes);
        this.aggregate = new StreamBuffer("combined", maxLines, maxBytes);
    }

    synchronized void appendStdout(byte[] bytes, int length) {
        stdout.append(bytes, length);
        aggregate.append(bytes, length);
    }

    synchronized void appendStderr(byte[] bytes, int length) {
        stderr.append(bytes, length);
        aggregate.append(bytes, length);
    }

    synchronized Snapshot snapshot(boolean persistIfTruncated) throws IOException {
        StreamSnapshot stdoutSnapshot = stdout.snapshot(persistIfTruncated);
        StreamSnapshot stderrSnapshot = stderr.snapshot(persistIfTruncated);
        StreamSnapshot aggregateSnapshot = aggregate.snapshot(persistIfTruncated);
        return new Snapshot(
                stdoutSnapshot,
                stderrSnapshot,
                aggregateSnapshot
        );
    }

    record Snapshot(
            StreamSnapshot stdout,
            StreamSnapshot stderr,
            StreamSnapshot aggregate
    ) {
        String content() {
            return aggregate.content();
        }

        boolean truncated() {
            return stdout.truncated() || stderr.truncated() || aggregate.truncated();
        }

        Map<String, Object> truncationDetails() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("truncated", truncated());
            details.put("aggregate", aggregate.truncationDetails());
            details.put("stdout", stdout.truncationDetails());
            details.put("stderr", stderr.truncationDetails());
            return details;
        }
    }

    record StreamSnapshot(
            String content,
            TextOutputTruncator.TruncationResult truncation,
            Path fullOutputPath
    ) {
        boolean truncated() {
            return truncation != null && truncation.truncated();
        }

        Map<String, Object> truncationDetails() {
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

    private static final class StreamBuffer {
        private final String streamName;
        private final StringBuilder output = new StringBuilder();
        private final int maxLines;
        private final int maxBytes;
        private Path fullOutputPath;

        private StreamBuffer(String streamName, int maxLines, int maxBytes) {
            this.streamName = streamName;
            this.maxLines = maxLines;
            this.maxBytes = maxBytes;
        }

        synchronized void append(byte[] bytes, int length) {
            if (bytes == null || length <= 0) {
                return;
            }
            output.append(new String(bytes, 0, length, StandardCharsets.UTF_8));
        }

        synchronized StreamSnapshot snapshot(boolean persistIfTruncated) throws IOException {
            String fullOutput = output.toString();
            TextOutputTruncator.TruncationResult truncation = TextOutputTruncator.truncateTail(
                    fullOutput,
                    maxLines,
                    maxBytes
            );
            if (persistIfTruncated && truncation.truncated()) {
                ensureFullOutputFile(fullOutput);
            }
            return new StreamSnapshot(
                    truncation.content(),
                    truncation,
                    fullOutputPath
            );
        }

        private void ensureFullOutputFile(String fullOutput) throws IOException {
            if (fullOutputPath != null) {
                return;
            }
            fullOutputPath = Files.createTempFile("aether-bash-" + streamName + "-", ".log");
            Files.writeString(fullOutputPath, fullOutput, StandardCharsets.UTF_8);
        }
    }
}
