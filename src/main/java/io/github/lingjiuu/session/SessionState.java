package io.github.lingjiuu.session;

import io.github.lingjiuu.context.EnvironmentContext;
import io.github.lingjiuu.llm.TokenUsage;
import io.github.lingjiuu.llm.TokenUsageInfo;

public class SessionState {

    private final String sessionId;
    private final long createdAt;
    private volatile long updatedAt;
    private volatile SessionStatus status = SessionStatus.IDLE;
    private EnvironmentContext referenceEnvironmentContext;
    private TokenUsageInfo tokenUsageInfo;
    private int turn = 1;

    public SessionState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.sessionId = sessionId;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = createdAt;
    }

    public String sessionId() {
        return sessionId;
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public SessionStatus status() {
        return status;
    }

    public int turn() {
        return turn;
    }

    public synchronized int nextTurn() {
        return turn++;
    }

    public synchronized EnvironmentContext referenceEnvironmentContext() {
        return referenceEnvironmentContext;
    }

    public synchronized void setReferenceEnvironmentContext(EnvironmentContext environmentContext) {
        referenceEnvironmentContext = environmentContext;
        touch();
    }

    public synchronized TokenUsageInfo tokenUsageInfo() {
        return tokenUsageInfo;
    }

    public synchronized void updateTokenUsage(TokenUsage usage, Long modelContextWindow) {
        tokenUsageInfo = TokenUsageInfo.append(tokenUsageInfo, usage, modelContextWindow);
        touch();
    }

    public synchronized void recomputeTokenUsage(long estimatedTotalTokens, Long modelContextWindow) {
        tokenUsageInfo = TokenUsageInfo.recomputeHistoryBaseline(
                tokenUsageInfo,
                estimatedTotalTokens,
                modelContextWindow
        );
        touch();
    }

    public synchronized void setTokenUsageFull(Long modelContextWindow) {
        if (modelContextWindow == null || modelContextWindow <= 0) {
            return;
        }
        tokenUsageInfo = TokenUsageInfo.recomputeHistoryBaseline(
                tokenUsageInfo,
                modelContextWindow,
                modelContextWindow
        );
        touch();
    }

    public synchronized void clearTokenUsage() {
        tokenUsageInfo = null;
        touch();
    }

    public synchronized void markRunning() {
        status = SessionStatus.RUNNING;
        touch();
    }

    public synchronized void markIdle() {
        status = SessionStatus.IDLE;
        touch();
    }

    public synchronized void markStopped() {
        status = SessionStatus.STOPPED;
        touch();
    }

    public synchronized void touch() {
        updatedAt = System.currentTimeMillis();
    }
}
