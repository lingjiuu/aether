package io.github.lingjiuu.tool.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class WorkspaceIgnoreMatcher {

    private final Path root;
    private final List<IgnoreRule> rules;

    private WorkspaceIgnoreMatcher(Path root, List<IgnoreRule> rules) {
        this.root = root.toAbsolutePath().normalize();
        this.rules = List.copyOf(rules);
    }

    public static WorkspaceIgnoreMatcher load(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        List<IgnoreRule> rules = new ArrayList<>();
        rules.add(IgnoreRule.directory(".git"));
        rules.add(IgnoreRule.directory("node_modules"));

        Path gitignore = normalizedRoot.resolve(".gitignore");
        if (Files.exists(gitignore)) {
            try {
                for (String line : Files.readAllLines(gitignore)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                        continue;
                    }
                    rules.add(IgnoreRule.from(trimmed));
                }
            } catch (IOException ignored) {
                // Ignore files are advisory; an unreadable .gitignore should not break read-only tools.
            }
        }
        return new WorkspaceIgnoreMatcher(normalizedRoot, rules);
    }

    public boolean isIgnored(Path absolutePath, boolean directory) {
        if (absolutePath == null) {
            return false;
        }
        Path normalized = absolutePath.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return true;
        }
        String relative = toPosix(root.relativize(normalized));
        if (relative.isBlank()) {
            return false;
        }
        for (IgnoreRule rule : rules) {
            if (rule.matches(relative, directory)) {
                return true;
            }
        }
        return false;
    }

    private static String toPosix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private record IgnoreRule(
            String pattern,
            boolean directoryOnly,
            boolean rootRelative,
            boolean hasSlash,
            Pattern regex
    ) {
        static IgnoreRule directory(String name) {
            return new IgnoreRule(name, true, false, false, Pattern.compile(globToRegex(name)));
        }

        static IgnoreRule from(String rawPattern) {
            String pattern = rawPattern;
            boolean directoryOnly = pattern.endsWith("/");
            if (directoryOnly) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            boolean rootRelative = pattern.startsWith("/");
            if (rootRelative) {
                pattern = pattern.substring(1);
            }
            return new IgnoreRule(
                    pattern,
                    directoryOnly,
                    rootRelative,
                    pattern.contains("/"),
                    Pattern.compile(globToRegex(pattern))
            );
        }

        boolean matches(String relative, boolean directory) {
            if (directoryOnly && !directory && !hasDirectoryPrefix(relative)) {
                return false;
            }
            if (!hasSlash) {
                for (String segment : relative.split("/")) {
                    if (regex.matcher(segment).matches()) {
                        return true;
                    }
                }
                return directoryOnly && relative.startsWith(pattern + "/");
            }
            if (rootRelative) {
                return regex.matcher(relative).matches() || relative.startsWith(pattern + "/");
            }
            return regex.matcher(relative).matches()
                    || relative.startsWith(pattern + "/")
                    || relative.contains("/" + pattern + "/");
        }

        private boolean hasDirectoryPrefix(String relative) {
            return relative.equals(pattern) || relative.startsWith(pattern + "/") || relative.contains("/" + pattern + "/");
        }
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
}
