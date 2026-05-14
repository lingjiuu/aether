package io.github.lingjiuu.tool.builtin;

final class TextMutationSupport {

    private static final char UTF_8_BOM = '\uFEFF';

    private TextMutationSupport() {
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

    static int firstChangedLine(String text, String needle) {
        int index = text.indexOf(needle);
        if (index < 0) {
            return -1;
        }
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static String simpleDiff(String oldText, String newText) {
        return prefixLines("-", oldText) + "\n" + prefixLines("+", newText);
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
            String restored = TextMutationSupport.normalizeLineEndings(normalizedContent)
                    .replace("\n", lineEnding);
            return hasBom ? UTF_8_BOM + restored : restored;
        }
    }
}
