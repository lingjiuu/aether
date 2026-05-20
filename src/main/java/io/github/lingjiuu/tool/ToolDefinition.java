package io.github.lingjiuu.tool;

import java.util.List;
import java.util.Map;

public interface ToolDefinition {

    String name();

    String label();

    String description();

    Map<String, Object> parametersSchema();

    default Object prepareArguments(Object arguments) {
        return arguments;
    }

    default ToolRiskLevel riskLevel() {
        return ToolRiskLevel.UNKNOWN;
    }

    default String promptSnippet() {
        return null;
    }

    default List<String> promptGuidelines() {
        return List.of();
    }

    ToolExecutionResult execute(ToolExecutionContext context);
}
