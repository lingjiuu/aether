package io.github.lingjiuu.tool.permission;

public final class PermissionDecision {

    public enum Action {
        ALLOW,
        ASK,
        DENY
    }

    private final Action action;
    private final String reason;

    private PermissionDecision(Action action, String reason) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        this.action = action;
        this.reason = reason;
    }

    public static PermissionDecision allow() {
        return new PermissionDecision(Action.ALLOW, null);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(Action.DENY, reason);
    }

    public static PermissionDecision ask(String reason) {
        return new PermissionDecision(Action.ASK, reason);
    }

    public Action action() {
        return action;
    }

    public String reason() {
        return reason;
    }

    public boolean allowed() {
        return action == Action.ALLOW;
    }
}
