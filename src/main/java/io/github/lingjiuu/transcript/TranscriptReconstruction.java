package io.github.lingjiuu.transcript;

import io.github.lingjiuu.context.EnvironmentContext;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.transcript.item.SessionMetaItem;

import java.util.List;

public record TranscriptReconstruction(
        String sessionId,
        SessionMetaItem sessionMeta,
        TranscriptModelSelection modelSelection,
        String sessionName,
        List<Message> messages,
        EnvironmentContext initialContextBaseline,
        List<UiEvent> timelineEvents,
        long lastEventSequence,
        String lastRecordId
) {

    public TranscriptReconstruction {
        messages = messages == null ? List.of() : List.copyOf(messages);
        timelineEvents = timelineEvents == null ? List.of() : List.copyOf(timelineEvents);
    }
}
