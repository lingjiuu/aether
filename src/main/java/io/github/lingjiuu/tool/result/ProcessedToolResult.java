package io.github.lingjiuu.tool.result;

import io.github.lingjiuu.message.ToolResultMessage;

import java.util.List;

public record ProcessedToolResult(
        ToolResultMessage message,
        List<ToolResultArtifactRef> artifactRefs
) {

    public ProcessedToolResult {
        artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
    }
}
