package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.ToolCallContent;

public final class ToolInvocation {

    private final AssistantMessage assistantMessage;
    private final ToolCallContent toolCall;
    private final ToolDefinition definition;

    private ToolInvocation(
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            ToolDefinition definition
    ) {
        if (toolCall == null) {
            throw new IllegalArgumentException("toolCall must not be null");
        }
        this.assistantMessage = assistantMessage;
        this.toolCall = toolCall;
        this.definition = definition;
    }

    public static ToolInvocation of(
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            ToolDefinition definition
    ) {
        return new ToolInvocation(assistantMessage, toolCall, definition);
    }

    public AssistantMessage assistantMessage() {
        return assistantMessage;
    }

    public ToolCallContent toolCall() {
        return toolCall;
    }

    public ToolDefinition definition() {
        return definition;
    }

    public String toolName() {
        return toolCall.getToolName();
    }

    public String toolCallId() {
        return toolCall.getToolCallId();
    }

    public boolean hasDefinition() {
        return definition != null;
    }
}
