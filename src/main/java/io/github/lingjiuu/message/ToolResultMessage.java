package io.github.lingjiuu.message;

import io.github.lingjiuu.message.content.MessageContent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResultMessage implements Message{

    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    @Builder.Default
    private List<MessageContent> contents = new ArrayList<>();

    private String toolCallId;

    private String toolName;

    private boolean isError;

    @Override
    public Role role() {
        return Role.TOOLRESULT;
    }

    @Override
    public List<MessageContent> messageContents() {
        return contents;
    }

    @Override
    public long timestamp() {
        return timestamp;
    }
}
