package io.github.lingjiuu.ui.history;

import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventPayloads;
import io.github.lingjiuu.protocol.UiEventType;
import junit.framework.TestCase;

import java.util.List;

public class UiHistoryStateTest extends TestCase {

    public void testCompactHistoryRendersCodexStyleBoundary() {
        var history = UiHistoryState.fromEvents("session-1", List.of(
                event(UiEventType.TURN_STARTED, 1L, null),
                event(UiEventType.COMPACT_STARTED, 2L, new UiEventPayloads.Compact("manual", 5, null)),
                event(UiEventType.COMPACT_FINISHED, 3L, new UiEventPayloads.Compact("raw compact summary", 5, 2)),
                event(UiEventType.TURN_COMPLETED, 4L, null)
        ));

        assertEquals(1, history.turns().size());
        var items = history.turns().getFirst().items();
        assertEquals(1, items.size());
        assertEquals("Context compacted", items.getFirst().text());
        assertEquals("COMPLETED", items.getFirst().status());
    }

    private UiEvent event(UiEventType type, Long sequence, io.github.lingjiuu.protocol.UiEventPayload payload) {
        return UiEvent.builder()
                .type(type)
                .sessionId("session-1")
                .turnId("turn-1")
                .turn(1)
                .sequence(sequence)
                .payload(payload)
                .build();
    }
}
