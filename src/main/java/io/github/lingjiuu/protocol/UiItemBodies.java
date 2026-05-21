package io.github.lingjiuu.protocol;

public final class UiItemBodies {

    private UiItemBodies() {
    }

    public record Text(String text) implements UiItemBody {
    }

    public record ToolCall(UiToolCall toolCall) implements UiItemBody {
    }

    public record ToolResult(UiToolResult toolResult) implements UiItemBody {
    }
}
