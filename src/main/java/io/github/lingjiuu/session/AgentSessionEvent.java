package io.github.lingjiuu.session;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSessionEvent {

    private Type type;

    private String sessionId;

    private Integer turn;

    private String delta;

    private String text;

    private UserMessage userMessage;

    private AssistantMessage assistantMessage;

    private ToolCallContent toolCall;

    private ToolResultMessage toolResult;

    private String errorMessage;

    public enum Type {
        RUN_START,
        USER_MESSAGE,
        TURN_START,
        ASSISTANT_TEXT_DELTA,
        REASONING_DELTA,
        ASSISTANT_MESSAGE,
        TOOL_CALL,
        TOOL_RESULT,
        FINAL_ANSWER,
        RUN_END,
        SESSION_RESET,
        ERROR
    }
}
