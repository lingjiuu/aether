package io.github.lingjiuu.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.tool.result.ToolDisplayResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResultMessage implements Message{

    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    @Builder.Default
    private List<MessageContent> contents = new ArrayList<>();

    private String toolCallId;

    private String toolName;

    private Object details;

    private ToolDisplayResult display;

    @JsonProperty("isError")
    private boolean isError;

    @JsonProperty("isError")
    public boolean isError() {
        return isError;
    }

    @Override
    public String id() {
        return id;
    }

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
