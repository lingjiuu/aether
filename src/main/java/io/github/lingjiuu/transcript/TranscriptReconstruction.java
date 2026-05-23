package io.github.lingjiuu.transcript;

import io.github.lingjiuu.context.InitialContextSnapshot;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.transcript.item.SessionMetaItem;

import java.util.List;

public record TranscriptReconstruction(
        String sessionId,
        SessionMetaItem sessionMeta,
        String sessionName,
        List<Message> messages,
        InitialContextSnapshot initialContextBaseline,
        List<UiEvent> timelineEvents,
        long lastEventSequence,
        String lastRecordId
) {

    public TranscriptReconstruction {
        messages = messages == null ? List.of() : List.copyOf(messages);
        timelineEvents = timelineEvents == null ? List.of() : List.copyOf(timelineEvents);
    }
}
