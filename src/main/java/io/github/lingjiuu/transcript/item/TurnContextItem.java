package io.github.lingjiuu.transcript.item;

import io.github.lingjiuu.context.InitialContextSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TurnContextItem implements TranscriptItem {

    private String turnId;

    private int turn;

    private InitialContextSnapshot initialContextBaseline;

    @Override
    public TranscriptItemType type() {
        return TranscriptItemType.TURN_CONTEXT;
    }
}
