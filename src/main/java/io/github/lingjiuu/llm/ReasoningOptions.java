package io.github.lingjiuu.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReasoningOptions {

    private ReasoningEffort reasoningEffort;
    private ReasoningSummaryEffort reasoningSummaryEffort;

    public enum ReasoningEffort {

        XHIGH,
        HIGH,
        MEDIUM,
        LOW,
        MINIMAL,
        NONE

    }

    public enum ReasoningSummaryEffort {

        AUTO,
        CONCISE,
        DETAILED

    }



}
