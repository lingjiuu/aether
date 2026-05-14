package io.github.lingjiuu.tool;

public class ToolPoolCompiler {

    private final ActiveToolSetCompiler activeToolSetCompiler;

    public ToolPoolCompiler() {
        this(new ActiveToolSetCompiler());
    }

    public ToolPoolCompiler(ActiveToolSetCompiler activeToolSetCompiler) {
        if (activeToolSetCompiler == null) {
            throw new IllegalArgumentException("activeToolSetCompiler must not be null");
        }
        this.activeToolSetCompiler = activeToolSetCompiler;
    }

    public ToolPoolSnapshot compile(ToolRegistry registry) {
        return new ToolPoolSnapshot(activeToolSetCompiler.compile(registry, null));
    }

    public ToolPoolSnapshot compile(ToolRegistry registry, java.util.List<String> activeToolNames) {
        return new ToolPoolSnapshot(activeToolSetCompiler.compile(registry, activeToolNames));
    }
}
