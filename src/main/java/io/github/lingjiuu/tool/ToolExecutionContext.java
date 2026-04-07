package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

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

    public void block(String reason) {
        this.blocked = true;
        this.blockedReason = reason;
    }
}
