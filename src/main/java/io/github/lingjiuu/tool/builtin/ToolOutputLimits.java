package io.github.lingjiuu.tool.builtin;

public final class ToolOutputLimits {

    public static final int DEFAULT_MAX_BYTES = 50 * 1024;
    public static final int READ_MAX_BYTES = 24 * 1024;
    public static final int READ_MAX_IMAGE_BASE64_BYTES = (int) (4.5 * 1024 * 1024);
    public static final int BASH_MAX_LINES = 2000;
    public static final int GLOB_DEFAULT_LIMIT = 100;
    public static final int GREP_DEFAULT_HEAD_LIMIT = 250;
    public static final int GREP_MAX_LINE_LENGTH = 500;

    private ToolOutputLimits() {
    }
}
