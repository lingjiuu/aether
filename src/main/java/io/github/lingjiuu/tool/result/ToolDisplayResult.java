package io.github.lingjiuu.tool.result;

public record ToolDisplayResult(String kind, String text, Object data) {

    public static ToolDisplayResult empty(String kind) {
        return new ToolDisplayResult(kind, null, null);
    }

    public static ToolDisplayResult of(String kind, Object data) {
        return new ToolDisplayResult(kind, null, data);
    }

    public static ToolDisplayResult text(String kind, String text, Object data) {
        return new ToolDisplayResult(kind, text, data);
    }
}
