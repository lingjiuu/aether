package io.github.lingjiuu.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiTokenUsage {

    private UiTokenCount total;

    private UiTokenCount last;

    private Long modelContextWindow;

    private Long contextTokenUsage;

    private Long autoCompactTokenLimit;
}
