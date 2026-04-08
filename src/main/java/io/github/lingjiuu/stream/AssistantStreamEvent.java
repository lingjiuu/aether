package io.github.lingjiuu.stream;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantStreamEvent {

    private Type type;

    private Integer contentIndex;

    private String delta;

    private String content;

    private String reason;

    private AssistantMessage partial;

    private AssistantMessage message;

    private AssistantMessage error;

    private ToolCallContent toolCall;

    public enum Type {
        START,
        TEXT_START,
        TEXT_DELTA,
        TEXT_END,
        THINKING_START,
        THINKING_DELTA,
        THINKING_END,
        TOOLCALL_START,
        TOOLCALL_DELTA,
        TOOLCALL_END,
        DONE,
        ERROR
    }
}
