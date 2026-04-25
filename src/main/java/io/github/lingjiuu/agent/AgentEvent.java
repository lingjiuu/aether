package io.github.lingjiuu.agent;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.AttachmentMessage;
import io.github.lingjiuu.message.SystemMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolExecutionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {

    private Type type;

    private Integer turn;

    private String delta;

    private AssistantMessage assistantMessage;

    private ToolCallContent toolCall;

    private ToolResultMessage toolResult;

    private SystemMessage systemMessage;

    private AttachmentMessage attachmentMessage;

    private ToolExecutionResult partialToolResult;

    private String text;

    public enum Type {
        RUN_START,
        TURN_START,
        ASSISTANT_TEXT_DELTA,
        REASONING_DELTA,
        ASSISTANT_MESSAGE,
        TOOL_CALL,
        TOOL_EXECUTION_START,
        TOOL_EXECUTION_UPDATE,
        TOOL_EXECUTION_END,
        TOOL_RESULT,
        SYSTEM_MESSAGE,
        ATTACHMENT_MESSAGE,
        FINAL_ANSWER,
        RUN_END
    }
}
