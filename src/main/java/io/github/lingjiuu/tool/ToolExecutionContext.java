package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionContext {

    private AssistantMessage assistantMessage;

    private ToolCallContent toolCall;

    private String toolCallId;

    private String toolName;

    private String argumentsJson;

    private Map<String, Object> arguments;

    private boolean blocked;

    private String blockedReason;

    private ToolExecutionResult result;

    private Consumer<ToolExecutionResult> updateSink;

    @Builder.Default
    private ToolCancellationToken cancellationToken = ToolCancellationToken.none();

    private Instant deadline;

    public void block(String reason) {
        this.blocked = true;
        this.blockedReason = reason;
    }

    public ToolCancellationToken cancellationToken() {
        return cancellationToken == null ? ToolCancellationToken.none() : cancellationToken;
    }

    public void throwIfCancellationRequested() {
        cancellationToken().throwIfCancellationRequested();
    }

    public Optional<Duration> remainingTimeout() {
        if (deadline == null) {
            return Optional.empty();
        }
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return Optional.of(Duration.ZERO);
        }
        return Optional.of(remaining);
    }

    public Duration remainingTimeoutOr(Duration fallback) {
        return remainingTimeout().orElse(fallback);
    }

    public void emitUpdate(ToolExecutionResult partialResult) {
        if (updateSink != null && partialResult != null) {
            updateSink.accept(partialResult);
        }
    }
}
