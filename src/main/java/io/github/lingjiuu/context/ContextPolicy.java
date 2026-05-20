package io.github.lingjiuu.context;

public record ContextPolicy(int maxToolResultChars, boolean autoCompactEnabled) {

    private static final ContextPolicy DEFAULTS = new ContextPolicy(16_000, true);

    public static ContextPolicy defaults() {
        return DEFAULTS;
    }
}
