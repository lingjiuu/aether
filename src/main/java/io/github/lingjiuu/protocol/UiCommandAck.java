package io.github.lingjiuu.protocol;

public record UiCommandAck(
        boolean accepted,
        String commandId,
        String sessionId,
        UiHistory history,
        String message
) {

    public static UiCommandAck accepted(String sessionId, String message) {
        return accepted(null, sessionId, message);
    }

    public static UiCommandAck accepted(String commandId, String sessionId, String message) {
        return new UiCommandAck(true, commandId, sessionId, null, message);
    }

    public static UiCommandAck accepted(String sessionId, UiHistory history, String message) {
        return accepted(null, sessionId, history, message);
    }

    public static UiCommandAck accepted(String commandId, String sessionId, UiHistory history, String message) {
        return new UiCommandAck(true, commandId, sessionId, history, message);
    }

    public static UiCommandAck rejected(String sessionId, String message) {
        return rejected(null, sessionId, message);
    }

    public static UiCommandAck rejected(String commandId, String sessionId, String message) {
        return new UiCommandAck(false, commandId, sessionId, null, message);
    }
}
