package io.github.lingjiuu.tool;

public record ToolCallResult<O>(O output, ToolFailure failure, ToolCallStatus status) {

    public ToolCallResult {
        status = status == null
                ? (failure == null ? ToolCallStatus.COMPLETED : ToolCallStatus.FAILED)
                : status;
    }

    public static <O> ToolCallResult<O> success(O output) {
        return new ToolCallResult<>(output, null, ToolCallStatus.COMPLETED);
    }

    public static <O> ToolCallResult<O> failedOutput(O output) {
        return new ToolCallResult<>(output, null, ToolCallStatus.FAILED);
    }

    public static <O> ToolCallResult<O> failure(ToolFailure failure) {
        return new ToolCallResult<>(null, failure, ToolCallStatus.FAILED);
    }

    public static <O> ToolCallResult<O> failure(ToolFailure failure, ToolCallStatus status) {
        return new ToolCallResult<>(null, failure, status);
    }

    public boolean hasFailure() {
        return failure != null;
    }

    public boolean isError() {
        return hasFailure() || status != ToolCallStatus.COMPLETED;
    }
}
