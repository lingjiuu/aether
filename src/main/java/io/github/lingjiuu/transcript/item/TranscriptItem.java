package io.github.lingjiuu.transcript.item;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "itemType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SessionMetaItem.class, name = "SESSION_META"),
        @JsonSubTypes.Type(value = MessageTranscriptItem.class, name = "MESSAGE"),
        @JsonSubTypes.Type(value = CompactedTranscriptItem.class, name = "COMPACTED"),
        @JsonSubTypes.Type(value = TurnContextItem.class, name = "TURN_CONTEXT"),
        @JsonSubTypes.Type(value = EventTranscriptItem.class, name = "EVENT")
})
public interface TranscriptItem {

    TranscriptItemType type();
}
