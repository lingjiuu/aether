package io.github.lingjiuu.tool;

public record ToolFailure(ToolFailureKind kind, String message) {

    public ToolFailure {
        kind = kind == null ? ToolFailureKind.RUNTIME : kind;
        message = message == null || message.isBlank() ? "Tool execution failed." : message;
    }

    public static ToolFailure schema(String message) {
        return new ToolFailure(ToolFailureKind.SCHEMA, message);
    }

    public static ToolFailure validation(String message) {
        return new ToolFailure(ToolFailureKind.VALIDATION, message);
    }

    public static ToolFailure permission(String message) {
        return new ToolFailure(ToolFailureKind.PERMISSION, message);
    }

    public static ToolFailure cancellation(String message) {
        return new ToolFailure(ToolFailureKind.CANCELLATION, message);
    }

    public static ToolFailure timeout(String message) {
        return new ToolFailure(ToolFailureKind.TIMEOUT, message);
    }

    public static ToolFailure runtime(String message) {
        return new ToolFailure(ToolFailureKind.RUNTIME, message);
    }
}
