package io.github.lingjiuu.tool;

import com.openai.models.responses.FunctionTool;

import java.util.List;

public interface ToolDefinition {

    String name();

    String label();

    String description();

    FunctionTool schema();

    default String promptSnippet() {
        return null;
    }

    default List<String> promptGuidelines() {
        return List.of();
    }

    default void beforeExecute(ToolExecutionContext context) {
    }

    ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate);

    default ToolExecutionResult afterExecute(ToolExecutionContext context) {
        return context.getResult();
    }
}
