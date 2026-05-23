package io.github.lingjiuu.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "payloadType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UiEventPayloads.Text.class, name = "text"),
        @JsonSubTypes.Type(value = UiEventPayloads.SessionName.class, name = "sessionName"),
        @JsonSubTypes.Type(value = UiEventPayloads.UserMessage.class, name = "userMessage"),
        @JsonSubTypes.Type(value = UiEventPayloads.ContextMessage.class, name = "contextMessage"),
        @JsonSubTypes.Type(value = UiEventPayloads.ItemStarted.class, name = "itemStarted"),
        @JsonSubTypes.Type(value = UiEventPayloads.ItemCompleted.class, name = "itemCompleted"),
        @JsonSubTypes.Type(value = UiEventPayloads.TextDelta.class, name = "textDelta"),
        @JsonSubTypes.Type(value = UiEventPayloads.ToolArgumentsDelta.class, name = "toolArgumentsDelta"),
        @JsonSubTypes.Type(value = UiEventPayloads.ToolArgumentsDone.class, name = "toolArgumentsDone"),
        @JsonSubTypes.Type(value = UiEventPayloads.ToolCall.class, name = "toolCall"),
        @JsonSubTypes.Type(value = UiEventPayloads.ToolExecution.class, name = "toolExecution"),
        @JsonSubTypes.Type(value = UiEventPayloads.ToolResult.class, name = "toolResult"),
        @JsonSubTypes.Type(value = UiEventPayloads.Approval.class, name = "approval"),
        @JsonSubTypes.Type(value = UiEventPayloads.TokenUsage.class, name = "tokenUsage"),
        @JsonSubTypes.Type(value = UiEventPayloads.Compact.class, name = "compact"),
        @JsonSubTypes.Type(value = UiEventPayloads.Error.class, name = "error")
})
public sealed interface UiEventPayload permits
        UiEventPayloads.Text,
        UiEventPayloads.SessionName,
        UiEventPayloads.UserMessage,
        UiEventPayloads.ContextMessage,
        UiEventPayloads.ItemStarted,
        UiEventPayloads.ItemCompleted,
        UiEventPayloads.TextDelta,
        UiEventPayloads.ToolArgumentsDelta,
        UiEventPayloads.ToolArgumentsDone,
        UiEventPayloads.ToolCall,
        UiEventPayloads.ToolExecution,
        UiEventPayloads.ToolResult,
        UiEventPayloads.Approval,
        UiEventPayloads.TokenUsage,
        UiEventPayloads.Compact,
        UiEventPayloads.Error {
}
