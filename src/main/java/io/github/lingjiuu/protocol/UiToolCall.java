package io.github.lingjiuu.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiToolCall {

    private String itemId;

    private Integer contentIndex;

    private String toolCallId;

    private String toolName;

    private String argumentsJson;
}
