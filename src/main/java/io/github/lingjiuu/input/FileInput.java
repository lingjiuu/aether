package io.github.lingjiuu.input;

import java.nio.file.Path;

public record FileInput(Path path) implements InputItem {

    public FileInput {
        if (path == null) {
            throw new IllegalArgumentException("file path must not be null");
        }
    }

    @Override
    public Kind kind() {
        return Kind.FILE;
    }
}
