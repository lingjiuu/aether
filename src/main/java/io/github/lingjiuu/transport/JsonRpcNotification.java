package io.github.lingjiuu.transport;

public record JsonRpcNotification(
        String method,
        Object params
) {
}
