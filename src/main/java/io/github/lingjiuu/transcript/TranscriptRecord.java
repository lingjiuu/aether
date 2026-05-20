package io.github.lingjiuu.transcript;

import io.github.lingjiuu.transcript.item.TranscriptItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptRecord {

    private String id;

    private String sessionId;

    private int turn;

    private long timestamp;

    private TranscriptItem item;
}
