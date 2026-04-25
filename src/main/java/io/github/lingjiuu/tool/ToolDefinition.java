package io.github.lingjiuu.tool;

import java.util.List;
import java.util.Map;

public interface ToolDefinition {

    String name();

    String label();

    String description();

    Map<String, Object> parametersSchema();

    default ToolSourceInfo sourceInfo() {
        return ToolSourceInfo.custom();
    }

    default ToolExecutionMode executionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    default ToolRiskLevel riskLevel() {
        return ToolRiskLevel.UNKNOWN;
    }

    default ToolResultPolicy resultPolicy() {
        return ToolResultPolicy.defaults();
    }

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
