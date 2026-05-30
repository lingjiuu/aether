package io.github.lingjiuu.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiToolUpdate {

    private String itemId;

    private String sourceItemId;

    private Integer contentIndex;

    private String toolCallId;

    private String toolName;

    private String status;

    private String text;

    private Long durationMs;

    private Object details;

    private Object display;

    private Boolean truncated;
}
