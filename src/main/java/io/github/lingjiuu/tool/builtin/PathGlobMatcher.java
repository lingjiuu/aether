package io.github.lingjiuu.tool.builtin;

import java.util.regex.Pattern;

final class PathGlobMatcher {

    private final String pattern;
    private final Pattern regex;
    private final boolean pathPattern;

    private PathGlobMatcher(String pattern) {
        this.pattern = pattern == null ? "" : pattern;
        this.pathPattern = this.pattern.contains("/");
        this.regex = Pattern.compile(globToRegex(this.pattern));
    }

    static PathGlobMatcher of(String pattern) {
        return new PathGlobMatcher(pattern);
    }

    boolean matches(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        if (pathPattern) {
            return regex.matcher(normalized).matches();
        }
        int slash = normalized.lastIndexOf('/');
        String basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return regex.matcher(basename).matches();
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doublestar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doublestar) {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (".[]{}()+-^$|\\".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        regex.append("$");
        return regex.toString();
    }

    @Override
    public String toString() {
        return pattern;
    }
}
