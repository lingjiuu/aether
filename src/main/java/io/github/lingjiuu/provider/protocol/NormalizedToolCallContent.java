package io.github.lingjiuu.provider.protocol;

public record NormalizedToolCallContent(
        String toolCallId,
        String toolName,
        String argumentsJson
) implements NormalizedContent {

    public NormalizedToolCallContent {
        toolCallId = toolCallId == null ? "" : toolCallId;
        toolName = toolName == null ? "" : toolName;
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }

    @Override
    public Type type() {
        return Type.TOOL_CALL;
    }
}
