package io.github.lingjiuu.message.attachment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TextAttachment implements Attachment {

    private String name;

    private String content;

    @Override
    public AttachmentType type() {
        return AttachmentType.TEXT;
    }

    @Override
    public String text() {
        if (name == null || name.isBlank()) {
            return content == null ? "" : content;
        }
        return name + ":\n" + (content == null ? "" : content);
    }
}
