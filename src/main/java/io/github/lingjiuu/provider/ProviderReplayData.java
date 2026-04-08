package io.github.lingjiuu.provider;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.lingjiuu.provider.openai.OpenAiReplayData;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "replayType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OpenAiReplayData.class, name = "openai")
})
public interface ProviderReplayData {

    String provider();
}
