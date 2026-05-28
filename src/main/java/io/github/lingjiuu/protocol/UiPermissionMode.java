package io.github.lingjiuu.protocol;

public record UiPermissionMode(
        String id,
        String name,
        String description,
        boolean current
) {
}
