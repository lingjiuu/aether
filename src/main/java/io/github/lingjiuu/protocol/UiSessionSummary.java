package io.github.lingjiuu.protocol;

public record UiSessionSummary(
        String sessionId,
        Long createdAt,
        Long updatedAt,
        String cwd,
        String modelProvider,
        String modelId,
        int recordCount
) {
}
