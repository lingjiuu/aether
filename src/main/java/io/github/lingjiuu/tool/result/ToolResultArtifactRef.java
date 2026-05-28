package io.github.lingjiuu.tool.result;

import java.nio.file.Path;

public record ToolResultArtifactRef(
        String kind,
        String label,
        Path path,
        long bytes,
        String mimeType,
        boolean hasMore,
        boolean json
) {

    public ToolResultArtifactRef {
        kind = kind == null || kind.isBlank() ? "tool_result_artifact" : kind;
        label = label == null || label.isBlank() ? null : label;
        mimeType = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
    }
}
