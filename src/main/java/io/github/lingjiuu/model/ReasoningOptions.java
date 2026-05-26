package io.github.lingjiuu.model;

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

    public String effortName() {
        return reasoningEffort == null ? null : reasoningEffort.name();
    }

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
