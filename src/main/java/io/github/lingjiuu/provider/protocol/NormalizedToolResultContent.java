package io.github.lingjiuu.provider.protocol;

import java.util.List;

public record NormalizedToolResultContent(
        String toolCallId,
        String toolName,
        String outputText,
        List<NormalizedImageContent> images,
        boolean error,
        Object details
) implements NormalizedContent {

    public NormalizedToolResultContent {
        toolCallId = toolCallId == null ? "" : toolCallId;
        toolName = toolName == null ? "" : toolName;
        outputText = outputText == null ? "" : outputText;
        images = images == null ? List.of() : List.copyOf(images);
    }

    public NormalizedToolResultContent(
            String toolCallId,
            String toolName,
            String outputText,
            boolean error,
            Object details
    ) {
        this(toolCallId, toolName, outputText, List.of(), error, details);
    }

    public static NormalizedToolResultContent syntheticError(String toolCallId) {
        return new NormalizedToolResultContent(
                toolCallId,
                "",
                "Tool execution did not return a result.",
                List.of(),
                true,
                null
        );
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }

    @Override
    public Type type() {
        return Type.TOOL_RESULT;
    }
}
