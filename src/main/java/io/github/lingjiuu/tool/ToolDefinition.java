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

    default boolean supportsParallelToolCalls() {
        return riskLevel() == ToolRiskLevel.READ_ONLY;
    }

    default String promptSnippet() {
        return null;
    }

    default List<String> promptGuidelines() {
        return List.of();
    }

    default boolean hasModelVisibleInstructions() {
        List<String> guidelines = promptGuidelines();
        return hasOneLineText(promptSnippet())
                || guidelines != null && guidelines.stream().anyMatch(ToolDefinition::hasOneLineText);
    }

    ToolExecutionResult execute(ToolExecutionContext context);

    private static boolean hasOneLineText(String value) {
        return value != null && !value.replaceAll("[\\r\\n]+", " ").trim().isEmpty();
    }
}
