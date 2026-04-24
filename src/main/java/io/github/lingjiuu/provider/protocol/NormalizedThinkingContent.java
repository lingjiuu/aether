package io.github.lingjiuu.provider.protocol;

public record NormalizedThinkingContent(String thinking) implements NormalizedContent {

    public NormalizedThinkingContent {
        thinking = thinking == null ? "" : thinking;
    }

    @Override
    public Type type() {
        return Type.THINKING;
    }
}
