package io.github.lingjiuu.transcript;

import io.github.lingjiuu.message.Message;
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

    private String parentRecordId;

    private String logicalParentRecordId;

    private int turn;

    private long timestamp;

    private Message message;
}
