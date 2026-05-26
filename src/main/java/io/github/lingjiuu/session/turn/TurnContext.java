package io.github.lingjiuu.session.turn;

import java.nio.file.Path;

public record TurnContext(
        TurnId turnId,
        String sessionId,
        int turn,
        Path cwd,
        String commandId
) {
    public TurnContext(TurnId turnId, String sessionId, int turn, Path cwd) {
        this(turnId, sessionId, turn, cwd, null);
    }
}
