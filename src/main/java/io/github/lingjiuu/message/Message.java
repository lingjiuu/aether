package io.github.lingjiuu.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.lingjiuu.message.content.MessageContent;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "messageType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UserMessage.class, name = "USER"),
        @JsonSubTypes.Type(value = AssistantMessage.class, name = "ASSISTANT"),
        @JsonSubTypes.Type(value = ToolResultMessage.class, name = "TOOLRESULT"),
        @JsonSubTypes.Type(value = SystemMessage.class, name = "SYSTEM"),
        @JsonSubTypes.Type(value = AttachmentMessage.class, name = "ATTACHMENT")
})
public interface Message {

    String id();

    Role role();

    List<MessageContent> messageContents();

    long timestamp();

    enum Role{
        USER,
        ASSISTANT,
        TOOLRESULT,
        SYSTEM,
        ATTACHMENT
    }

}
