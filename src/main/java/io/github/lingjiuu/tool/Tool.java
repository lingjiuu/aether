package io.github.lingjiuu.tool;

import java.util.Map;

public interface Tool {

    String name();

    String label();

    String description();

    Map<String, Object> parametersSchema();

    default ToolSpec spec() {
        return ToolSpec.of(
                name(),
                label(),
                description(),
                parametersSchema(),
                riskLevel()
        );
    }

    default Object prepareArguments(Object arguments) {
        return arguments;
    }

    default ToolRiskLevel riskLevel() {
        return ToolRiskLevel.UNKNOWN;
    }

    default boolean supportsParallelToolCalls() {
        return riskLevel() == ToolRiskLevel.READ_ONLY;
    }

    default Map<String, Object> validateArguments(String argumentsJson) {
        return spec().validateArguments(argumentsJson, this::prepareArguments);
    }

    ToolExecutionResult execute(ToolInvocation invocation);
}
