package io.github.lingjiuu.context;

public record ContextPolicy(int maxToolResultChars) {

    private static final ContextPolicy DEFAULTS = new ContextPolicy(16_000);

    public static ContextPolicy defaults() {
        return DEFAULTS;
    }
}
