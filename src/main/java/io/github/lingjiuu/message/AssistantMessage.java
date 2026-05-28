package io.github.lingjiuu.message;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.model.client.ModelErrorCode;
import io.github.lingjiuu.model.client.ModelErrorInfo;
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

    private ModelErrorInfo errorInfo;

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

    @JsonIgnore
    public boolean isAborted() {
        return stopReason == StopReason.ABORTED;
    }

    @JsonIgnore
    public boolean isError() {
        return stopReason == StopReason.ERROR;
    }

    @JsonIgnore
    public boolean isContextWindowExceeded() {
        if (errorInfo != null && errorInfo.contextWindowExceeded()) {
            return true;
        }
        if (!isError() || errorMessage == null || errorMessage.isBlank()) {
            return false;
        }

        return ModelErrorCode.fromMessage(errorMessage, null) == ModelErrorCode.CONTEXT_WINDOW_EXCEEDED;
    }

    @JsonIgnore
    public boolean isRetryableStreamFailure() {
        if (errorInfo != null) {
            return errorInfo.retryableAsStreamFailure();
        }
        if (!isError() || errorMessage == null || errorMessage.isBlank()) {
            return false;
        }

        ModelErrorCode code = ModelErrorCode.fromMessage(errorMessage, null);
        return code != null && code.retryableAsStreamFailure(null);
    }

    @JsonIgnore
    public boolean isRetryableRequestFailure() {
        return errorInfo != null && errorInfo.retryableAsRequestFailure();
    }

    public enum StopReason{
        STOP,
        LENGTH,
        TOOLUSE,
        ERROR,
        ABORTED
    }
}
