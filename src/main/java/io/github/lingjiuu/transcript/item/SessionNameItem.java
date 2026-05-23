package io.github.lingjiuu.transcript.item;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionNameItem implements TranscriptItem {

    private String sessionId;

    private String name;

    @Override
    public TranscriptItemType type() {
        return TranscriptItemType.SESSION_NAME;
    }
}
