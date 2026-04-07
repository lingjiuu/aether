package io.github.lingjiuu.tool;

@FunctionalInterface
public interface ToolUpdateCallback {

    void onUpdate(ToolExecutionResult partialResult);
}
