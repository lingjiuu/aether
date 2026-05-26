package io.github.lingjiuu.model.client;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.wire.WireReplayData;
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

    private String itemId;

    private String toolCallId;

    private String toolName;

    private Integer contentIndex;

    private String delta;

    private String content;

    private String reason;

    private AssistantMessage partial;

    private AssistantMessage message;

    private AssistantMessage error;

    private ToolCallContent toolCall;

    private WireReplayData providerState;

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
