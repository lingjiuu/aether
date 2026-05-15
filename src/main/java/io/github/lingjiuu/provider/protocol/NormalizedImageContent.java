package io.github.lingjiuu.provider.protocol;

public record NormalizedImageContent(String data, String mimeType) implements NormalizedContent {

    public NormalizedImageContent {
        data = data == null ? "" : data;
        mimeType = mimeType == null ? "" : mimeType;
    }

    @Override
    public Type type() {
        return Type.IMAGE;
    }
}
