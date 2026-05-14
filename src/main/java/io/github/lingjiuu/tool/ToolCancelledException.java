package io.github.lingjiuu.tool;

public class ToolCancelledException extends RuntimeException {

    public ToolCancelledException() {
        this("Tool execution cancelled.");
    }

    public ToolCancelledException(String message) {
        super(message == null || message.isBlank() ? "Tool execution cancelled." : message);
    }
}
