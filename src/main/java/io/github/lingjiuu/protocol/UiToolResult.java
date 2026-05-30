package io.github.lingjiuu.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiToolResult {

    private String itemId;

    private String sourceItemId;

    private Integer contentIndex;

    private String toolCallId;

    private String toolName;

    private String text;

    private boolean error;

    private String status;

    private Long durationMs;

    private Object details;

    private Object display;

    private Boolean truncated;
}
