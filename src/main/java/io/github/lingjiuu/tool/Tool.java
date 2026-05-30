package io.github.lingjiuu.tool;

import io.github.lingjiuu.tool.result.ToolResultPolicy;
import io.github.lingjiuu.tool.result.ModelToolResult;
import io.github.lingjiuu.tool.result.ToolDisplayResult;
import io.github.lingjiuu.tool.result.ToolResultContext;

import java.util.Map;

public interface Tool<I, O> {

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

    default ToolResultPolicy resultPolicy() {
        return ToolResultPolicy.defaultPolicy();
    }

    default Map<String, Object> validateArguments(String argumentsJson) {
        return spec().validateArguments(argumentsJson, this::prepareArguments);
    }

    I parseInput(String argumentsJson);

    default Map<String, Object> permissionArguments(I input) {
        return Map.of();
    }

    default ValidationResult validateInput(I input, ToolUseContext context) {
        return ValidationResult.ok();
    }

    ToolCallResult<O> call(I input, ToolUseContext context);

    ModelToolResult toModelResult(O output, ToolResultContext<I, O> context);

    default ToolDisplayResult toDisplayResult(O output, ToolResultContext<I, O> context) {
        return ToolDisplayResult.empty(name());
    }
}
