package io.github.lingjiuu.llm;

public record TokenUsageInfo(
        TokenUsage totalTokenUsage,
        TokenUsage lastTokenUsage,
        Long modelContextWindow
) {

    public static TokenUsageInfo append(TokenUsageInfo current, TokenUsage lastUsage, Long modelContextWindow) {
        if ((lastUsage == null || lastUsage.isEmpty()) && current == null && modelContextWindow == null) {
            return null;
        }

        TokenUsage total = current == null || current.totalTokenUsage == null
                ? TokenUsage.empty()
                : current.totalTokenUsage;
        TokenUsage last = current == null || current.lastTokenUsage == null
                ? TokenUsage.empty()
                : current.lastTokenUsage;
        Long contextWindow = modelContextWindow != null
                ? modelContextWindow
                : current == null ? null : current.modelContextWindow;

        if (lastUsage != null && !lastUsage.isEmpty()) {
            total = total.add(lastUsage);
            last = lastUsage;
        }
        return new TokenUsageInfo(total, last, contextWindow);
    }

    public static TokenUsageInfo recomputeHistoryBaseline(
            TokenUsageInfo current,
            long estimatedTotalTokens,
            Long modelContextWindow
    ) {
        TokenUsage total = current == null || current.totalTokenUsage == null
                ? TokenUsage.empty()
                : current.totalTokenUsage;
        Long contextWindow = modelContextWindow != null
                ? modelContextWindow
                : current == null ? null : current.modelContextWindow;
        TokenUsage baseline = new TokenUsage(0, 0, 0, 0, Math.max(0, estimatedTotalTokens));
        return new TokenUsageInfo(total, baseline, contextWindow);
    }
}
