package io.github.lingjiuu.message.attachment;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "attachmentType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextAttachment.class, name = "TEXT")
})
public interface Attachment {

    AttachmentType type();

    String text();
}
