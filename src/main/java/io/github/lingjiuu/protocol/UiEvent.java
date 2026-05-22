package io.github.lingjiuu.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiEvent {

    private UiEventType type;

    private Long sequence;

    private Long timestampMs;

    private String sessionId;

    private String commandId;

    private String turnId;

    private Integer turn;

    private UiEventPayload payload;

    public void stamp(long sequence, long timestampMs) {
        if (this.sequence == null) {
            this.sequence = sequence;
        }
        if (this.timestampMs == null) {
            this.timestampMs = timestampMs;
        }
    }
}
