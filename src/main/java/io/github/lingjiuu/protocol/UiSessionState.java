package io.github.lingjiuu.protocol;

import java.util.List;

public record UiSessionState(
        String sessionId,
        String appVersion,
        String status,
        int messageCount,
        int availableSkillCount,
        boolean canContinue,
        List<String> activeToolNames,
        UiSessionSummary summary,
        String reasoningEffort,
        String permissionMode,
        UiTokenUsage tokenUsage
) {

    public UiSessionState {
        activeToolNames = activeToolNames == null ? List.of() : List.copyOf(activeToolNames);
    }
}
