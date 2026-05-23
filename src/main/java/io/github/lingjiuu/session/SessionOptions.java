package io.github.lingjiuu.session;

import java.nio.file.Path;

public record SessionOptions(Path cwd) {

    public static SessionOptions defaults() {
        return new SessionOptions(null);
    }

    public static SessionOptions cwd(Path cwd) {
        return new SessionOptions(cwd);
    }
}
