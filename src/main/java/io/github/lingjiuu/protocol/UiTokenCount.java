package io.github.lingjiuu.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiTokenCount {

    private long inputTokens;

    private long cachedInputTokens;

    private long outputTokens;

    private long reasoningOutputTokens;

    private long totalTokens;
}
