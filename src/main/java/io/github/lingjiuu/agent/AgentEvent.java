package io.github.lingjiuu.agent;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
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

    private String text;

    public enum Type {
        RUN_START,
        TURN_START,
        ASSISTANT_TEXT_DELTA,
        REASONING_DELTA,
        ASSISTANT_MESSAGE,
        TOOL_CALL,
        TOOL_RESULT,
        FINAL_ANSWER,
        RUN_END
    }
}
