package io.github.lingjiuu.tool.builtin.edit;

import java.util.ArrayList;
import java.util.List;

final class EditApplier {

    private static final char UTF_8_BOM = '\uFEFF';

    private EditApplier() {
    }

    static TextState capture(String content) {
        String safeContent = content == null ? "" : content;
        boolean hasBom = safeContent.startsWith(String.valueOf(UTF_8_BOM));
        String withoutBom = hasBom ? safeContent.substring(1) : safeContent;
        String lineEnding = withoutBom.contains("\r\n") ? "\r\n" : "\n";
        return new TextState(hasBom, lineEnding, normalizeLineEndings(withoutBom));
    }

    static String normalizeLineEndings(String text) {
        return (text == null ? "" : text)
                .replace("\r\n", "\n")
                .replace("\r", "\n");
    }

    static int countOccurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    static AppliedEdit apply(String normalizedContent, String oldText, String newText, boolean replaceAll, String path) {
        String normalizedOldText = normalizeLineEndings(oldText);
        if (normalizedOldText.isEmpty()) {
            throw new IllegalArgumentException("old_string must not be empty");
        }
        String safeContent = normalizedContent == null ? "" : normalizedContent;
        int occurrences = countOccurrences(safeContent, normalizedOldText);
        if (occurrences == 0) {
            throw new IllegalArgumentException("exact old_string not found in " + path);
        }
        if (occurrences > 1 && !replaceAll) {
            throw new IllegalArgumentException("old_string matched multiple times in " + path + "; it must be unique");
        }

        List<Integer> matchIndexes = new ArrayList<>();
        int index = 0;
        while ((index = safeContent.indexOf(normalizedOldText, index)) >= 0) {
            matchIndexes.add(index);
            index += normalizedOldText.length();
            if (!replaceAll) {
                break;
            }
        }

        String normalizedNewText = normalizeLineEndings(newText);
        String newContent = safeContent;
        for (int i = matchIndexes.size() - 1; i >= 0; i--) {
            int matchIndex = matchIndexes.get(i);
            newContent = newContent.substring(0, matchIndex)
                    + normalizedNewText
                    + newContent.substring(matchIndex + normalizedOldText.length());
        }
        if (safeContent.equals(newContent)) {
            throw new IllegalArgumentException("No changes made to " + path + ". The replacements produced identical content.");
        }

        int firstChangedLine = lineNumberAtOffset(safeContent, matchIndexes.getFirst());
        return new AppliedEdit(
                newContent,
                matchIndexes.size(),
                firstChangedLine,
                repeatedDiff(normalizedOldText, normalizedNewText, matchIndexes.size())
        );
    }

    static int lineNumberAtOffset(String text, int index) {
        if (text == null || index < 0) {
            return -1;
        }
        int line = 1;
        int end = Math.min(index, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static String simpleDiff(String oldText, String newText) {
        return prefixLines("-", oldText) + "\n" + prefixLines("+", newText);
    }

    private static String repeatedDiff(String oldText, String newText, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(simpleDiff(oldText, newText));
        }
        return builder.toString();
    }

    private static String prefixLines(String prefix, String text) {
        String[] lines = normalizeLineEndings(text).split("\n", -1);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(prefix).append(lines[i]);
        }
        return builder.toString();
    }

    record TextState(boolean hasBom, String lineEnding, String normalizedContent) {
        String restore(String normalizedContent) {
            String restored = EditApplier.normalizeLineEndings(normalizedContent)
                    .replace("\n", lineEnding);
            return hasBom ? UTF_8_BOM + restored : restored;
        }
    }

    record AppliedEdit(
            String newContent,
            int replacements,
            int firstChangedLine,
            String diff
    ) {
    }
}
