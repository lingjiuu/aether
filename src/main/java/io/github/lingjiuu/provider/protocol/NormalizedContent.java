package io.github.lingjiuu.provider.protocol;

public interface NormalizedContent {

    Type type();

    enum Type {
        TEXT,
        THINKING,
        TOOL_CALL,
        TOOL_RESULT
    }
}
