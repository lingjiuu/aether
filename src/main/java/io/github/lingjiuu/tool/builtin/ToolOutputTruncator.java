package io.github.lingjiuu.tool.builtin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class ToolOutputTruncator {

    private ToolOutputTruncator() {
    }

    static TruncationResult truncateHead(String content, int maxBytes) {
        String safeContent = content == null ? "" : content;
        int totalBytes = byteLength(safeContent);
        List<String> lines = List.of(safeContent.split("\n", -1));
        if (totalBytes <= maxBytes) {
            return new TruncationResult(
                    safeContent,
                    false,
                    null,
                    lines.size(),
                totalBytes,
                lines.size(),
                totalBytes,
                false,
                false,
                0,
                maxBytes
            );
        }

        if (!lines.isEmpty() && byteLength(lines.getFirst()) > maxBytes) {
            return new TruncationResult(
                    "",
                    true,
                    "bytes",
                    lines.size(),
                    totalBytes,
                    0,
                    0,
                    true,
                    false,
                    0,
                    maxBytes
            );
        }

        List<String> output = new ArrayList<>();
        int outputBytes = 0;
        for (String line : lines) {
            int lineBytes = byteLength(line) + (output.isEmpty() ? 0 : 1);
            if (outputBytes + lineBytes > maxBytes) {
                break;
            }
            output.add(line);
            outputBytes += lineBytes;
        }

        String truncated = String.join("\n", output);
        return new TruncationResult(
                truncated,
                true,
                "bytes",
                lines.size(),
                totalBytes,
                output.size(),
                byteLength(truncated),
                false,
                false,
                0,
                maxBytes
        );
    }

    static TruncationResult truncateTail(String content, int maxLines, int maxBytes) {
        String safeContent = content == null ? "" : content;
        int totalBytes = byteLength(safeContent);
        List<String> lines = List.of(safeContent.split("\n", -1));
        int totalLines = lines.size();
        if (totalLines <= maxLines && totalBytes <= maxBytes) {
            return new TruncationResult(
                    safeContent,
                    false,
                    null,
                    totalLines,
                    totalBytes,
                    totalLines,
                    totalBytes,
                    false,
                    false,
                    maxLines,
                    maxBytes
            );
        }

        List<String> output = new ArrayList<>();
        int outputBytes = 0;
        String truncatedBy = "lines";
        boolean lastLinePartial = false;
        for (int i = lines.size() - 1; i >= 0 && output.size() < maxLines; i--) {
            String line = lines.get(i);
            int lineBytes = byteLength(line) + (output.isEmpty() ? 0 : 1);
            if (outputBytes + lineBytes > maxBytes) {
                truncatedBy = "bytes";
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

        if (output.size() >= maxLines && totalLines > maxLines && !"bytes".equals(truncatedBy)) {
            truncatedBy = "lines";
        }
        String truncated = String.join("\n", output);
        return new TruncationResult(
                truncated,
                true,
                truncatedBy,
                totalLines,
                totalBytes,
                output.size(),
                byteLength(truncated),
                false,
                lastLinePartial,
                maxLines,
                maxBytes
        );
    }

    static LineTruncation truncateLine(String line, int maxChars) {
        String safeLine = line == null ? "" : line;
        if (safeLine.length() <= maxChars) {
            return new LineTruncation(safeLine, false);
        }
        return new LineTruncation(safeLine.substring(0, maxChars) + "... [truncated]", true);
    }

    static String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1fKB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String tailByBytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int start = bytes.length - maxBytes;
        while (start < bytes.length && (bytes[start] & 0xc0) == 0x80) {
            start++;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    record TruncationResult(
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

    record LineTruncation(String text, boolean wasTruncated) {
    }
}
