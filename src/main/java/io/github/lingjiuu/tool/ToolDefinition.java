package io.github.lingjiuu.tool;

import io.github.lingjiuu.tool.render.ToolRenderRequest;
import io.github.lingjiuu.tool.render.ToolRenderShell;
import io.github.lingjiuu.tool.render.ToolRenderedOutput;

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

    default ToolRenderShell renderShell() {
        return ToolRenderShell.DEFAULT;
    }

    default ToolRenderedOutput renderCall(ToolRenderRequest request) {
        return null;
    }

    default ToolRenderedOutput renderResult(ToolRenderRequest request) {
        return null;
    }

    default void beforeExecute(ToolExecutionContext context) {
    }

    ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate);

    default ToolExecutionResult afterExecute(ToolExecutionContext context) {
        return context.getResult();
    }
}
