package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.file.ReadFileState;
import io.github.lingjiuu.tool.result.ToolDisplayResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ToolUseContext {

    private Tool<?, ?> tool;

    private ToolCallContent toolCall;

    @Builder.Default
    private ToolCancellationToken cancellationToken = ToolCancellationToken.none();

    private Instant deadline;

    private Consumer<ToolDisplayResult> updateSink;

    private ReadFileState readFileState;

    public String toolName() {
        return toolCall == null ? null : toolCall.getToolName();
    }

    public String toolCallId() {
        return toolCall == null ? null : toolCall.getToolCallId();
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

    public void emitUpdate(ToolDisplayResult partialResult) {
        if (updateSink != null && partialResult != null) {
            updateSink.accept(partialResult);
        }
    }

    public ReadFileState readFileState() {
        return readFileState;
    }
}
