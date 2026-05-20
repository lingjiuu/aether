package io.github.lingjiuu.prompt;

import io.github.lingjiuu.context.ContextProjection;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.tool.ToolDefinition;

import java.util.List;

public record PromptBuildInput(
        SessionConfig config,
        ContextProjection contextProjection,
        List<ToolDefinition> tools
) {

    public PromptBuildInput {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
