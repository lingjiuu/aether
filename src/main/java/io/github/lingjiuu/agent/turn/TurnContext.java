package io.github.lingjiuu.agent.turn;

import java.nio.file.Path;

public record TurnContext(
        TurnId turnId,
        String sessionId,
        int turn,
        Path cwd
) {
}
