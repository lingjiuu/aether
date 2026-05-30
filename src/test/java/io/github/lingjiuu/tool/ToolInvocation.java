package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.file.ReadFileState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocation {

    private Tool<?, ?> tool;

    private ToolCallContent toolCall;

    @Builder.Default
    private Map<String, Object> arguments = Map.of();

    @Builder.Default
    private ToolCancellationToken cancellationToken = ToolCancellationToken.none();

    private Instant deadline;

    private ReadFileState readFileState;

    public ToolCancellationToken cancellationToken() {
        return cancellationToken == null ? ToolCancellationToken.none() : cancellationToken;
    }

    public ReadFileState readFileState() {
        return readFileState;
    }
}
