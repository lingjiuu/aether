package io.github.lingjiuu.tool.builtin.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class StructuredDiff {

    private static final int CONTEXT_LINES = 3;
    private static final int MAX_LCS_CELLS = 2_000_000;

    private StructuredDiff() {
    }

    public static Map<String, Object> hunkSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "oldStart", Map.of("type", "number"),
                        "oldLines", Map.of("type", "number"),
                        "newStart", Map.of("type", "number"),
                        "newLines", Map.of("type", "number"),
                        "lines", Map.of("type", "array", "items", Map.of("type", "string"))
                ),
                "required", List.of("oldStart", "oldLines", "newStart", "newLines", "lines")
        );
    }

    public static Map<String, Object> gitDiffSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "filename", Map.of("type", "string"),
                        "status", Map.of("type", "string", "enum", List.of("modified", "added")),
                        "additions", Map.of("type", "number"),
                        "deletions", Map.of("type", "number"),
                        "changes", Map.of("type", "number"),
                        "patch", Map.of("type", "string"),
                        "repository", Map.of(
                                "type", List.of("string", "null"),
                                "description", "GitHub owner/repo when available"
                        )
                ),
                "required", List.of("filename", "status", "additions", "deletions", "changes", "patch")
        );
    }

    public static List<Hunk> patch(String oldContent, String newContent) {
        List<String> oldLines = splitLines(convertLeadingTabsToSpaces(oldContent));
        List<String> newLines = splitLines(convertLeadingTabsToSpaces(newContent));
        if (oldLines.equals(newLines)) {
            return List.of();
        }
        long cells = (long) (oldLines.size() + 1) * (long) (newLines.size() + 1);
        if (cells > MAX_LCS_CELLS) {
            return singleHunk(oldLines, newLines);
        }
        return hunks(operations(oldLines, newLines));
    }

    public static String toDiffText(List<Hunk> hunks) {
        if (hunks == null || hunks.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Hunk hunk : hunks) {
            lines.addAll(hunk.lines());
        }
        return String.join("\n", lines);
    }

    private static List<Operation> operations(List<String> oldLines, List<String> newLines) {
        int oldSize = oldLines.size();
        int newSize = newLines.size();
        int[][] lcs = new int[oldSize + 1][newSize + 1];
        for (int i = oldSize - 1; i >= 0; i--) {
            for (int j = newSize - 1; j >= 0; j--) {
                if (oldLines.get(i).equals(newLines.get(j))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        List<Operation> operations = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < oldSize && j < newSize) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                operations.add(new Operation(' ', oldLines.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                operations.add(new Operation('-', oldLines.get(i++)));
            } else {
                operations.add(new Operation('+', newLines.get(j++)));
            }
        }
        while (i < oldSize) {
            operations.add(new Operation('-', oldLines.get(i++)));
        }
        while (j < newSize) {
            operations.add(new Operation('+', newLines.get(j++)));
        }
        return operations;
    }

    private static List<Hunk> hunks(List<Operation> operations) {
        List<Hunk> hunks = new ArrayList<>();
        int scan = 0;
        while (scan < operations.size()) {
            int changeStart = nextChange(operations, scan);
            if (changeStart < 0) {
                break;
            }
            int lastChange = changeStart;
            int unchangedSinceChange = 0;
            int cursor = changeStart + 1;
            while (cursor < operations.size()) {
                if (operations.get(cursor).change()) {
                    if (unchangedSinceChange > CONTEXT_LINES * 2) {
                        break;
                    }
                    lastChange = cursor;
                    unchangedSinceChange = 0;
                } else {
                    unchangedSinceChange++;
                }
                cursor++;
            }

            int hunkStart = Math.max(0, changeStart - CONTEXT_LINES);
            int hunkEnd = Math.min(operations.size(), lastChange + CONTEXT_LINES + 1);
            hunks.add(buildHunk(operations, hunkStart, hunkEnd));
            scan = hunkEnd;
        }
        return Collections.unmodifiableList(hunks);
    }

    private static int nextChange(List<Operation> operations, int start) {
        for (int i = start; i < operations.size(); i++) {
            if (operations.get(i).change()) {
                return i;
            }
        }
        return -1;
    }

    private static Hunk buildHunk(List<Operation> operations, int start, int end) {
        int oldStart = 1;
        int newStart = 1;
        for (int i = 0; i < start; i++) {
            Operation operation = operations.get(i);
            if (operation.prefix() != '+') {
                oldStart++;
            }
            if (operation.prefix() != '-') {
                newStart++;
            }
        }

        int oldLines = 0;
        int newLines = 0;
        List<String> lines = new ArrayList<>();
        for (int i = start; i < end; i++) {
            Operation operation = operations.get(i);
            if (operation.prefix() != '+') {
                oldLines++;
            }
            if (operation.prefix() != '-') {
                newLines++;
            }
            lines.add(operation.prefix() + operation.text());
        }
        return new Hunk(oldStart, oldLines, newStart, newLines, lines);
    }

    private static List<Hunk> singleHunk(List<String> oldLines, List<String> newLines) {
        List<String> lines = new ArrayList<>();
        for (String oldLine : oldLines) {
            lines.add("-" + oldLine);
        }
        for (String newLine : newLines) {
            lines.add("+" + newLine);
        }
        return List.of(new Hunk(1, oldLines.size(), 1, newLines.size(), lines));
    }

    private static String convertLeadingTabsToSpaces(String content) {
        String safeContent = content == null ? "" : content;
        if (!safeContent.contains("\t")) {
            return safeContent;
        }
        String[] lines = safeContent.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int tabs = 0;
            while (tabs < lines[i].length() && lines[i].charAt(tabs) == '\t') {
                tabs++;
            }
            if (tabs > 0) {
                lines[i] = "  ".repeat(tabs) + lines[i].substring(tabs);
            }
        }
        return String.join("\n", lines);
    }

    private static List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalized.split("\n", -1);
        int length = normalized.endsWith("\n") ? parts.length - 1 : parts.length;
        List<String> lines = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            lines.add(parts[i]);
        }
        return lines;
    }

    public record Hunk(
            int oldStart,
            int oldLines,
            int newStart,
            int newLines,
            List<String> lines
    ) {
        public Hunk {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    private record Operation(char prefix, String text) {
        boolean change() {
            return prefix == '-' || prefix == '+';
        }
    }
}
