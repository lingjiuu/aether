package io.github.lingjiuu.transport;

public record JsonRpcError(
        long code,
        String message,
        Object data
) {
}
