package io.github.lingjiuu.protocol;

public final class UiCommandPayloads {

    private UiCommandPayloads() {
    }

    public record SubmitUserInput(String text) implements UiCommandPayload {
    }

    public record NewSession(String cwd) implements UiCommandPayload {
    }

    public record SetSessionName(String name) implements UiCommandPayload {
    }

    public record ResumeSession(String sessionId) implements UiCommandPayload {
    }

    public record SetModel(
            String providerId,
            String modelId,
            String reasoningEffort
    ) implements UiCommandPayload {
    }

    public record ApprovalResponse(
            String approvalId,
            boolean approved,
            String reason
    ) implements UiCommandPayload {
    }
}
