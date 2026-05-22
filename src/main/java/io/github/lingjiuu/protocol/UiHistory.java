package io.github.lingjiuu.protocol;

import java.util.List;

public record UiHistory(
        String sessionId,
        List<UiTurn> turns
) {

    public UiHistory {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
