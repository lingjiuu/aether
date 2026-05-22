package io.github.lingjiuu.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
                .payload(new UiCommandPayloads.SubmitUserInput(text))
                .build();
    }

    public static UiCommand resumeSession(String sessionId) {
        return UiCommand.builder()
                .commandId(newCommandId())
                .type(UiCommandType.RESUME_SESSION)
                .payload(new UiCommandPayloads.ResumeSession(sessionId))
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
