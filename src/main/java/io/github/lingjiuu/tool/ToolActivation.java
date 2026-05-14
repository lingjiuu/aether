package io.github.lingjiuu.tool;

public final class ToolActivation {

    private final ToolDefinition definition;
    private final boolean active;
    private final String reason;

    private ToolActivation(ToolDefinition definition, boolean active, String reason) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        this.definition = definition;
        this.active = active;
        this.reason = reason;
    }

    public static ToolActivation active(ToolDefinition definition) {
        return new ToolActivation(definition, true, null);
    }

    public static ToolActivation inactive(ToolDefinition definition, String reason) {
        return new ToolActivation(definition, false, reason);
    }

    public ToolDefinition definition() {
        return definition;
    }

    public boolean active() {
        return active;
    }

    public String reason() {
        return reason;
    }
}
