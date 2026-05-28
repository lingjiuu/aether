package io.github.lingjiuu.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "payloadType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UiCommandPayloads.SubmitUserInput.class, name = "submitUserInput"),
        @JsonSubTypes.Type(value = UiCommandPayloads.NewSession.class, name = "newSession"),
        @JsonSubTypes.Type(value = UiCommandPayloads.SetSessionName.class, name = "setSessionName"),
        @JsonSubTypes.Type(value = UiCommandPayloads.ResumeSession.class, name = "resumeSession"),
        @JsonSubTypes.Type(value = UiCommandPayloads.SetModel.class, name = "setModel"),
        @JsonSubTypes.Type(value = UiCommandPayloads.SetPermissionMode.class, name = "setPermissionMode"),
        @JsonSubTypes.Type(value = UiCommandPayloads.ApprovalResponse.class, name = "approvalResponse")
})
public sealed interface UiCommandPayload permits
        UiCommandPayloads.SubmitUserInput,
        UiCommandPayloads.NewSession,
        UiCommandPayloads.SetSessionName,
        UiCommandPayloads.ResumeSession,
        UiCommandPayloads.SetModel,
        UiCommandPayloads.SetPermissionMode,
        UiCommandPayloads.ApprovalResponse {
}
