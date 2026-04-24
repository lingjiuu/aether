package io.github.lingjiuu.provider.protocol;

public record NormalizedTextContent(String text) implements NormalizedContent {

    public NormalizedTextContent {
        text = text == null ? "" : text;
    }

    @Override
    public Type type() {
        return Type.TEXT;
    }
}
