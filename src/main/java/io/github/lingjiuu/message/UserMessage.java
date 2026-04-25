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
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMessage implements Message {

    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    @Builder.Default
    private List<MessageContent> contents = new ArrayList<>();

    @Override
    public String id() {
        return id;
    }

    @Override
    public Role role() {
        return Role.USER;
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
