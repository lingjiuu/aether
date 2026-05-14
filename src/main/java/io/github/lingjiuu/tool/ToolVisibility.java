package io.github.lingjiuu.tool;

public final class ToolVisibility {

    private final ToolDefinition definition;
    private final boolean visible;
    private final String reason;

    private ToolVisibility(ToolDefinition definition, boolean visible, String reason) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        this.definition = definition;
        this.visible = visible;
        this.reason = reason;
    }

    public static ToolVisibility visible(ToolDefinition definition) {
        return new ToolVisibility(definition, true, null);
    }

    public static ToolVisibility hidden(ToolDefinition definition, String reason) {
        return new ToolVisibility(definition, false, reason);
    }

    public ToolDefinition definition() {
        return definition;
    }

    public boolean visible() {
        return visible;
    }

    public String reason() {
        return reason;
    }

    static ToolVisibility fromActivation(ToolActivation activation) {
        if (activation.active()) {
            return visible(activation.definition());
        }
        return hidden(activation.definition(), activation.reason());
    }
}
