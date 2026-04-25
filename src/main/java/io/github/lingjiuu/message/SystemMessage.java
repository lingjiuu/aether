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
public class SystemMessage implements Message {

    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    private Subtype subtype;

    @Builder.Default
    private List<MessageContent> contents = new ArrayList<>();

    @Builder.Default
    private List<String> removedMessageIds = new ArrayList<>();

    @Builder.Default
    private List<ToolResultReplacement> toolResultReplacements = new ArrayList<>();

    private long estimatedTokensFreed;

    @Override
    public String id() {
        return id;
    }

    @Override
    public Role role() {
        return Role.SYSTEM;
    }

    @Override
    public List<MessageContent> messageContents() {
        return contents;
    }

    @Override
    public long timestamp() {
        return timestamp;
    }

    public enum Subtype {
        INFORMATIONAL,
        CONTENT_REPLACEMENT,
        SNIP_BOUNDARY
    }

    public record ToolResultReplacement(
            String toolCallId,
            String replacement
    ) {
    }
}
