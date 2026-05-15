package io.github.lingjiuu.tool.builtin;

public final class ToolOutputLimits {

    public static final int DEFAULT_MAX_BYTES = 50 * 1024;
    public static final int READ_MAX_BYTES = 24 * 1024;
    public static final int BASH_MAX_LINES = 2000;
    public static final int LS_DEFAULT_LIMIT = 500;
    public static final int FIND_DEFAULT_LIMIT = 1000;
    public static final int GREP_DEFAULT_LIMIT = 100;
    public static final int GREP_MAX_LINE_LENGTH = 500;

    private ToolOutputLimits() {
    }
}
