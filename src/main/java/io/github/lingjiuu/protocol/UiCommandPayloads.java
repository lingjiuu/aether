package io.github.lingjiuu.protocol;

import java.util.List;

public final class UiCommandPayloads {

    private UiCommandPayloads() {
    }

    public record SubmitUserInput(List<TurnInputItem> items) implements UiCommandPayload {
        public SubmitUserInput {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record TurnInputItem(
            String type,
            String text,
            String path,
            String name
    ) {
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
