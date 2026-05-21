package io.github.lingjiuu.message;

import io.github.lingjiuu.message.content.MessageContent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ContextMessage implements Message {

    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    private ContextKind kind;

    @Builder.Default
    private List<MessageContent> contents = new ArrayList<>();

    @Override
    public String id() {
        return id;
    }

    @Override
    public Role role() {
        return Role.CONTEXT;
    }

    @Override
    public List<MessageContent> messageContents() {
        return contents;
    }

    @Override
    public long timestamp() {
        return timestamp;
    }

    public enum ContextKind {
        ENVIRONMENT,
        RESOURCE,
        SKILL,
        INFORMATIONAL
    }
}
