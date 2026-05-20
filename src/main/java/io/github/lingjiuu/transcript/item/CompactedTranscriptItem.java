package io.github.lingjiuu.transcript.item;

import io.github.lingjiuu.message.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompactedTranscriptItem implements TranscriptItem {

    private String summary;

    private int originalMessageCount;

    private int replacementMessageCount;

    private int preservedUserMessageCount;

    @Builder.Default
    private List<Message> replacementMessages = new ArrayList<>();

    @Override
    public TranscriptItemType type() {
        return TranscriptItemType.COMPACTED;
    }
}
