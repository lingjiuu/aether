package io.github.lingjiuu.message.content;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "contentType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextContent.class, name = "TEXT"),
        @JsonSubTypes.Type(value = ImageContent.class, name = "IMAGE"),
        @JsonSubTypes.Type(value = ThinkingContent.class, name = "THINKING"),
        @JsonSubTypes.Type(value = ToolCallContent.class, name = "TOOLCALL")
})
public interface MessageContent {

    Type type();

    public enum Type{
        TEXT,
        IMAGE,
        THINKING,
        TOOLCALL
    }
}
