package io.github.lingjiuu.input;

import java.nio.file.Path;

public record LocalImageInput(Path path) implements InputItem {

    public LocalImageInput {
        if (path == null) {
            throw new IllegalArgumentException("image path must not be null");
        }
    }

    @Override
    public Kind kind() {
        return Kind.LOCAL_IMAGE;
    }
}
