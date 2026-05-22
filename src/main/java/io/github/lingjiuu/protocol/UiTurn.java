package io.github.lingjiuu.protocol;

import java.util.List;

public record UiTurn(
        String turnId,
        String commandId,
        int turn,
        String status,
        List<UiHistoryItem> items
) {

    public UiTurn {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
