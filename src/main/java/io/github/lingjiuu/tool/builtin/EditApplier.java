package io.github.lingjiuu.tool.builtin;

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

    static AppliedEdits applyEditsToNormalizedContent(String normalizedContent, java.util.List<Edit> edits, String path) {
        if (edits == null || edits.isEmpty()) {
            throw new IllegalArgumentException("edits must contain at least one replacement");
        }

        java.util.List<NormalizedEdit> normalizedEdits = new java.util.ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            Edit edit = edits.get(i);
            if (edit == null) {
                throw new IllegalArgumentException(editLabel(i, edits.size()) + " must not be null");
            }
            String oldText = normalizeLineEndings(edit.oldText());
            if (oldText.isEmpty()) {
                throw new IllegalArgumentException(editLabel(i, edits.size()) + ".oldText must not be empty");
            }
            normalizedEdits.add(new NormalizedEdit(oldText, normalizeLineEndings(edit.newText())));
        }

        String safeContent = normalizedContent == null ? "" : normalizedContent;
        java.util.List<MatchedEdit> matchedEdits = new java.util.ArrayList<>();
        for (int i = 0; i < normalizedEdits.size(); i++) {
            NormalizedEdit edit = normalizedEdits.get(i);
            int occurrences = countOccurrences(safeContent, edit.oldText());
            if (occurrences == 0) {
                throw new IllegalArgumentException(notFoundMessage(path, i, normalizedEdits.size()));
            }
            if (occurrences > 1) {
                throw new IllegalArgumentException(duplicateMessage(path, i, normalizedEdits.size(), occurrences));
            }
            matchedEdits.add(new MatchedEdit(
                    i,
                    safeContent.indexOf(edit.oldText()),
                    edit.oldText().length(),
                    edit.oldText(),
                    edit.newText()
            ));
        }

        matchedEdits.sort(java.util.Comparator.comparingInt(MatchedEdit::matchIndex));
        for (int i = 1; i < matchedEdits.size(); i++) {
            MatchedEdit previous = matchedEdits.get(i - 1);
            MatchedEdit current = matchedEdits.get(i);
            if (previous.matchIndex() + previous.matchLength() > current.matchIndex()) {
                throw new IllegalArgumentException("edits[" + previous.editIndex() + "] and edits["
                        + current.editIndex() + "] overlap in " + path
                        + ". Merge them into one edit or target disjoint regions.");
            }
        }

        String newContent = safeContent;
        for (int i = matchedEdits.size() - 1; i >= 0; i--) {
            MatchedEdit edit = matchedEdits.get(i);
            newContent = newContent.substring(0, edit.matchIndex())
                    + edit.newText()
                    + newContent.substring(edit.matchIndex() + edit.matchLength());
        }
        if (safeContent.equals(newContent)) {
            throw new IllegalArgumentException("No changes made to " + path + ". The replacements produced identical content.");
        }

        int firstChangedLine = lineNumberAtOffset(safeContent, matchedEdits.getFirst().matchIndex());
        return new AppliedEdits(
                safeContent,
                newContent,
                matchedEdits.size(),
                firstChangedLine,
                simpleDiff(matchedEdits)
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

    private static String simpleDiff(java.util.List<MatchedEdit> matchedEdits) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < matchedEdits.size(); i++) {
            MatchedEdit edit = matchedEdits.get(i);
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(simpleDiff(edit.oldText(), edit.newText()));
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

    record Edit(String oldText, String newText) {
    }

    record AppliedEdits(
            String baseContent,
            String newContent,
            int replacements,
            int firstChangedLine,
            String diff
    ) {
    }

    private record NormalizedEdit(String oldText, String newText) {
    }

    private record MatchedEdit(
            int editIndex,
            int matchIndex,
            int matchLength,
            String oldText,
            String newText
    ) {
    }

    private static String editLabel(int editIndex, int totalEdits) {
        return totalEdits == 1 ? "oldText" : "edits[" + editIndex + "]";
    }

    private static String notFoundMessage(String path, int editIndex, int totalEdits) {
        if (totalEdits == 1) {
            return "exact oldText not found in " + path;
        }
        return "edits[" + editIndex + "] exact oldText not found in " + path;
    }

    private static String duplicateMessage(String path, int editIndex, int totalEdits, int occurrences) {
        if (totalEdits == 1) {
            return "oldText matched multiple times in " + path + "; it must be unique";
        }
        return "edits[" + editIndex + "] matched " + occurrences + " times in " + path + "; each oldText must be unique";
    }
}
