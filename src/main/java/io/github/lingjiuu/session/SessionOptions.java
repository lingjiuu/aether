package io.github.lingjiuu.session;

import java.nio.file.Path;

public record SessionOptions(
        Path cwd,
        String modelProvider,
        String modelId,
        String reasoningEffort
) {

    public static SessionOptions defaults() {
        return new SessionOptions(null, null, null, null);
    }

    public static SessionOptions cwd(Path cwd) {
        return new SessionOptions(cwd, null, null, null);
    }

    public static SessionOptions resume(Path cwd, String modelProvider, String modelId, String reasoningEffort) {
        return new SessionOptions(cwd, modelProvider, modelId, reasoningEffort);
    }
}
