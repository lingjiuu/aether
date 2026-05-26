package io.github.lingjiuu.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiCommand {

    private String commandId;

    private UiCommandType type;

    private UiCommandPayload payload;

    public static UiCommand submitUserInput(String text) {
        return UiCommand.builder()
                .commandId(newCommandId())
                .type(UiCommandType.SUBMIT_USER_INPUT)
                .payload(new UiCommandPayloads.SubmitUserInput(List.of(new UiCommandPayloads.TurnInputItem(
                        "text",
                        text,
                        null,
                        null
                ))))
                .build();
    }

    public static UiCommand resumeSession(String sessionId) {
        return UiCommand.builder()
                .commandId(newCommandId())
                .type(UiCommandType.RESUME_SESSION)
                .payload(new UiCommandPayloads.ResumeSession(sessionId))
                .build();
    }

    public static UiCommand newSession(String cwd) {
        return UiCommand.builder()
                .commandId(newCommandId())
                .type(UiCommandType.NEW_SESSION)
                .payload(new UiCommandPayloads.NewSession(cwd))
                .build();
    }

    public static UiCommand setSessionName(String name) {
        return UiCommand.builder()
                .commandId(newCommandId())
                .type(UiCommandType.SET_SESSION_NAME)
                .payload(new UiCommandPayloads.SetSessionName(name))
                .build();
    }

    public static UiCommand setModel(String providerId, String modelId, String reasoningEffort) {
        return UiCommand.builder()
                .commandId(newCommandId())
                .type(UiCommandType.SET_MODEL)
                .payload(new UiCommandPayloads.SetModel(providerId, modelId, reasoningEffort))
                .build();
    }

    public static UiCommand simple(UiCommandType type) {
        return UiCommand.builder()
                .commandId(newCommandId())
                .type(type)
                .build();
    }

    public static String newCommandId() {
        return UUID.randomUUID().toString();
    }
}
