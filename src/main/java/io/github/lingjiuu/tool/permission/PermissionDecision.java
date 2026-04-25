package io.github.lingjiuu.tool.permission;

import java.util.Map;

public final class PermissionDecision {

    private final PermissionMode mode;
    private final String reason;
    private final String source;
    private final Map<String, Object> updatedArguments;

    private PermissionDecision(
            PermissionMode mode,
            String reason,
            String source,
            Map<String, Object> updatedArguments
    ) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        this.mode = mode;
        this.reason = reason;
        this.source = source;
        this.updatedArguments = updatedArguments == null ? null : Map.copyOf(updatedArguments);
    }

    public static PermissionDecision allow() {
        return new PermissionDecision(PermissionMode.ALLOW, null, "default", null);
    }

    public static PermissionDecision allow(Map<String, Object> updatedArguments) {
        return new PermissionDecision(PermissionMode.ALLOW, null, "default", updatedArguments);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(PermissionMode.DENY, reason, "default", null);
    }

    public static PermissionDecision ask(String reason) {
        return new PermissionDecision(PermissionMode.ASK, reason, "default", null);
    }

    public static PermissionDecision of(PermissionMode mode, String reason, String source) {
        return new PermissionDecision(mode, reason, source, null);
    }

    public PermissionMode mode() {
        return mode;
    }

    public String reason() {
        return reason;
    }

    public String source() {
        return source;
    }

    public Map<String, Object> updatedArguments() {
        return updatedArguments;
    }

    public boolean allowed() {
        return mode == PermissionMode.ALLOW;
    }
}
