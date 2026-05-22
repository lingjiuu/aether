package io.github.lingjiuu.protocol;

import java.util.List;

public record UiEventPage(
        String sessionId,
        long afterSequence,
        List<UiEvent> events,
        long nextAfterSequence,
        boolean hasMore,
        boolean replayRequired
) {

    public UiEventPage {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
