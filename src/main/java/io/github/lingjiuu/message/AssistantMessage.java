package io.github.lingjiuu.message;

import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.wire.WireReplayData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantMessage implements Message{

    public static AssistantMessage aborted() {
        return AssistantMessage.builder()
                .stopReason(StopReason.ABORTED)
                .contents(List.of(TextContent.builder()
                        .text("")
                        .build()))
                .build();
    }

    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    private String responseId;

    private String provider;

    private String model;

    @Builder.Default
    private List<MessageContent> contents = new ArrayList<>();

    private StopReason stopReason;

    @Builder.Default
    private Map<String, Object> usage = new LinkedHashMap<>();

    private String errorMessage;

    private WireReplayData providerState;


    @Override
    public String id() {
        return id;
    }

    @Override
    public Role role() {
        return Role.ASSISTANT;
    }

    @Override
    public List<MessageContent> messageContents() {
        return contents;
    }

    @Override
    public long timestamp() {
        return timestamp;
    }

    public enum StopReason{
        STOP,
        LENGTH,
        TOOLUSE,
        ERROR,
        ABORTED
    }
}
