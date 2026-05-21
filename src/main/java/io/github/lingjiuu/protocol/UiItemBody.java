package io.github.lingjiuu.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "bodyType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UiItemBodies.Text.class, name = "text"),
        @JsonSubTypes.Type(value = UiItemBodies.ToolCall.class, name = "toolCall"),
        @JsonSubTypes.Type(value = UiItemBodies.ToolResult.class, name = "toolResult")
})
public sealed interface UiItemBody permits
        UiItemBodies.Text,
        UiItemBodies.ToolCall,
        UiItemBodies.ToolResult {
}
