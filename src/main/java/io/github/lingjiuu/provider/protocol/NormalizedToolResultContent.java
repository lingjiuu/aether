package io.github.lingjiuu.provider.protocol;

public record NormalizedToolResultContent(
        String toolCallId,
        String toolName,
        String outputText,
        boolean error,
        Object details
) implements NormalizedContent {

    public NormalizedToolResultContent {
        toolCallId = toolCallId == null ? "" : toolCallId;
        toolName = toolName == null ? "" : toolName;
        outputText = outputText == null ? "" : outputText;
    }

    public static NormalizedToolResultContent syntheticError(String toolCallId) {
        return new NormalizedToolResultContent(
                toolCallId,
                "",
                "Tool execution did not return a result.",
                true,
                null
        );
    }

    @Override
    public Type type() {
        return Type.TOOL_RESULT;
    }
}
