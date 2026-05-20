package io.github.lingjiuu.transcript.item;

import io.github.lingjiuu.message.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageTranscriptItem implements TranscriptItem {

    private Message message;

    @Override
    public TranscriptItemType type() {
        return TranscriptItemType.MESSAGE;
    }
}
