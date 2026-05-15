package io.github.lingjiuu.provider.protocol;

public interface NormalizedContent {

    Type type();

    enum Type {
        TEXT,
        IMAGE,
        THINKING,
        TOOL_CALL,
        TOOL_RESULT
    }
}
