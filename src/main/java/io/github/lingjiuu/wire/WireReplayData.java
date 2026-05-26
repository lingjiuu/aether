package io.github.lingjiuu.wire;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.lingjiuu.wire.openai.OpenAiReplayData;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "replayType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OpenAiReplayData.class, name = "openai")
})
public interface WireReplayData {

    String provider();
}
