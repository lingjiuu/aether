package io.github.lingjiuu.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "payloadType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UiCommandPayloads.SubmitUserInput.class, name = "submitUserInput"),
        @JsonSubTypes.Type(value = UiCommandPayloads.ResumeSession.class, name = "resumeSession"),
        @JsonSubTypes.Type(value = UiCommandPayloads.ApprovalResponse.class, name = "approvalResponse")
})
public sealed interface UiCommandPayload permits
        UiCommandPayloads.SubmitUserInput,
        UiCommandPayloads.ResumeSession,
        UiCommandPayloads.ApprovalResponse {
}
