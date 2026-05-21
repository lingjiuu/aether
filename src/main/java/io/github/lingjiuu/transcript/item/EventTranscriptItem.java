package io.github.lingjiuu.transcript.item;

import io.github.lingjiuu.protocol.UiEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventTranscriptItem implements TranscriptItem {

    private UiEvent event;

    @Override
    public TranscriptItemType type() {
        return TranscriptItemType.EVENT;
    }
}
