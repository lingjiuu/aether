package io.github.lingjiuu.transcript;

import io.github.lingjiuu.context.InitialContextSnapshot;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.transcript.item.SessionMetaItem;

import java.util.List;

public record TranscriptReconstruction(
        String sessionId,
        SessionMetaItem sessionMeta,
        List<Message> messages,
        InitialContextSnapshot initialContextBaseline,
        String lastRecordId
) {

    public TranscriptReconstruction {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
