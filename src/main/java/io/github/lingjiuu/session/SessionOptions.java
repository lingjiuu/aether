package io.github.lingjiuu.session;

import io.github.lingjiuu.transcript.TranscriptModelSelection;
import io.github.lingjiuu.transcript.TranscriptReconstruction;
import io.github.lingjiuu.transcript.item.SessionMetaItem;

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

    public static SessionOptions resumeFrom(TranscriptReconstruction reconstruction) {
        SessionMetaItem meta = reconstruction == null ? null : reconstruction.sessionMeta();
        TranscriptModelSelection selection = reconstruction == null ? null : reconstruction.modelSelection();
        Path cwd = meta == null || meta.getCwd() == null || meta.getCwd().isBlank()
                ? null
                : Path.of(meta.getCwd());
        return resume(
                cwd,
                selection == null ? null : selection.providerId(),
                selection == null ? null : selection.modelId(),
                selection == null ? null : selection.reasoningEffort()
        );
    }

    public boolean hasModelOverride() {
        return !isBlank(modelProvider)
                || !isBlank(modelId)
                || !isBlank(reasoningEffort);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
