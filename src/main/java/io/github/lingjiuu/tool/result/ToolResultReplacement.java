package io.github.lingjiuu.tool.result;

import io.github.lingjiuu.message.ToolResultMessage;

import java.util.List;

public record ToolResultReplacement(
        String messageId,
        String toolCallId,
        String toolBatchId,
        ToolResultMessage replacementMessage,
        List<ToolResultArtifactRef> artifactRefs
) {

    public ToolResultReplacement {
        artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
    }
}
