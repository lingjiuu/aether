package io.github.lingjiuu.ui.command;

import io.github.lingjiuu.event.EventSink;
import io.github.lingjiuu.input.TurnInput;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.ReasoningOptions;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.llm.ModelOption;
import io.github.lingjiuu.llm.ModelSelection;
import io.github.lingjiuu.protocol.UiCommand;
import io.github.lingjiuu.protocol.UiCommandAck;
import io.github.lingjiuu.protocol.UiCommandPayloads;
import io.github.lingjiuu.protocol.UiCommandType;
import io.github.lingjiuu.protocol.UiModelCatalog;
import io.github.lingjiuu.protocol.UiModelInfo;
import io.github.lingjiuu.protocol.UiModelSelection;
import io.github.lingjiuu.protocol.UiSessionSummary;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.session.SessionOptions;
import io.github.lingjiuu.session.SessionStatus;
import io.github.lingjiuu.tool.permission.ApprovalHandler;
import io.github.lingjiuu.transcript.TranscriptRecord;
import io.github.lingjiuu.transcript.item.MessageTranscriptItem;
import io.github.lingjiuu.transcript.item.SessionMetaItem;
import io.github.lingjiuu.transcript.item.SessionNameItem;
import io.github.lingjiuu.ui.approval.ApprovalCoordinator;
import io.github.lingjiuu.ui.history.UiHistoryState;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

public class CommandManager implements AutoCloseable {

    private final SessionFactory sessionFactory;
    private final SessionOptions defaultSessionOptions;
    private final EventSink eventSink;
    private final ApprovalCoordinator approvalCoordinator;
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "aether-ui-commands");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Session session;

    public CommandManager(
            SessionFactory sessionFactory,
            EventSink eventSink,
            ApprovalHandler approvalHandler
    ) {
        this(sessionFactory, SessionOptions.defaults(), eventSink, approvalHandler);
    }

    public CommandManager(
            SessionFactory sessionFactory,
            SessionOptions defaultSessionOptions,
            EventSink eventSink,
            ApprovalHandler approvalHandler
    ) {
        if (sessionFactory == null) {
            throw new IllegalArgumentException("sessionFactory must not be null");
        }
        this.sessionFactory = sessionFactory;
        this.defaultSessionOptions = defaultSessionOptions == null ? SessionOptions.defaults() : defaultSessionOptions;
        this.eventSink = eventSink;
        this.approvalCoordinator = new ApprovalCoordinator(approvalHandler);
        this.session = configure(sessionFactory.openSession(this.defaultSessionOptions));
    }

    public UiCommandAck handle(UiCommand command) {
        if (command == null || command.getType() == null) {
            return UiCommandAck.rejected(sessionId(), "Command type is required.");
        }
        String commandId = commandId(command);
        Future<UiCommandAck> future;
        try {
            future = dispatcher.submit(() -> handleOnMailbox(command, commandId));
        } catch (RejectedExecutionException e) {
            return UiCommandAck.rejected(commandId, sessionId(), "command mailbox is closed");
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UiCommandAck.rejected(commandId, sessionId(), "command interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return UiCommandAck.rejected(commandId, sessionId(), cause.getMessage());
        }
    }

    public synchronized Session currentSession() {
        return session;
    }

    public synchronized String sessionId() {
        return session == null ? null : session.sessionId();
    }

    public synchronized java.util.List<UiSessionSummary> listSessions() {
        if (sessionFactory.config().transcriptStore() == null) {
            return java.util.List.of();
        }
        return sessionFactory.config().transcriptStore()
                .listSessionIds()
                .stream()
                .map(this::sessionSummary)
                .toList();
    }

    @Override
    public synchronized void close() {
        flush();
        closeCurrentSession();
        dispatcher.shutdownNow();
    }

    public void flush() {
        try {
            Future<?> future = dispatcher.submit(() -> {
            });
            future.get();
        } catch (RejectedExecutionException e) {
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new RuntimeException("Failed to flush command mailbox.", e);
        }
    }

    private UiCommandAck handleOnMailbox(UiCommand command, String commandId) {
        try {
            return switch (command.getType()) {
                case SUBMIT_USER_INPUT -> submit(command, commandId);
                case NEW_SESSION -> newSession(command, commandId);
                case CLOSE_SESSION -> closeSession(commandId);
                case SET_SESSION_NAME -> setSessionName(command, commandId);
                case RESUME_SESSION -> resume(command, commandId);
                case COMPACT -> compact(commandId);
                case CONTINUE -> continueSession(commandId);
                case CANCEL_TURN -> cancelTurn(commandId);
                case SET_MODEL -> setModel(command, commandId);
                case APPROVAL_RESPONSE -> approvalResponse(command, commandId);
            };
        } catch (RuntimeException e) {
            return UiCommandAck.rejected(commandId, sessionId(), e.getMessage());
        }
    }

    private UiCommandAck submit(UiCommand command, String commandId) {
        if (!(command.getPayload() instanceof UiCommandPayloads.SubmitUserInput input)
                || input.items().isEmpty()) {
            return UiCommandAck.rejected(commandId, sessionId(), "Input items are required.");
        }
        session.submitAsync(turnInput(input), commandId);
        return UiCommandAck.accepted(commandId, sessionId(), "submitted");
    }

    private TurnInput turnInput(UiCommandPayloads.SubmitUserInput input) {
        TurnInput.Builder builder = TurnInput.builder();
        for (UiCommandPayloads.TurnInputItem item : input.items()) {
            switch (item.type()) {
                case "text" -> builder.text(requiredText(item.text(), "text item text").trim());
                case "localImage" -> builder.localImage(Path.of(requiredText(item.path(), "localImage item path")));
                case "skill" -> builder.skill(
                        blankToNull(item.name()),
                        item.path() == null || item.path().isBlank() ? null : Path.of(item.path())
                );
                default -> throw new IllegalArgumentException("Unknown input item type: " + item.type());
            }
        }
        return builder.build();
    }

    private String requiredText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private UiCommandAck newSession(UiCommand command, String commandId) {
        switchSession(sessionFactory.openSession(newSessionOptions(command)));
        return UiCommandAck.accepted(commandId, sessionId(), history(), "new session");
    }

    private UiCommandAck closeSession(String commandId) {
        switchSession(sessionFactory.openSession(defaultSessionOptions));
        return UiCommandAck.accepted(commandId, sessionId(), history(), "session closed");
    }

    private UiCommandAck setSessionName(UiCommand command, String commandId) {
        if (!(command.getPayload() instanceof UiCommandPayloads.SetSessionName input)
                || input.name() == null
                || input.name().isBlank()) {
            return UiCommandAck.rejected(commandId, sessionId(), "Session name is required.");
        }
        session.setSessionName(input.name());
        return UiCommandAck.accepted(commandId, sessionId(), "session name updated");
    }

    private UiCommandAck resume(UiCommand command, String commandId) {
        if (!(command.getPayload() instanceof UiCommandPayloads.ResumeSession input)
                || input.sessionId() == null
                || input.sessionId().isBlank()) {
            return UiCommandAck.rejected(commandId, sessionId(), "usage: /resume <session-id>");
        }
        if (sessionFactory.config().transcriptStore() == null
                || !sessionFactory.config().transcriptStore().exists(input.sessionId())) {
            return UiCommandAck.rejected(commandId, sessionId(), "transcript not found for session: " + input.sessionId());
        }
        switchSession(sessionFactory.resumeSession(input.sessionId()));
        return UiCommandAck.accepted(commandId, sessionId(), history(), "resumed session");
    }

    private UiCommandAck compact(String commandId) {
        session.compactAsync(commandId);
        return UiCommandAck.accepted(commandId, sessionId(), "compact submitted");
    }

    private UiCommandAck continueSession(String commandId) {
        session.runContinueAsync(commandId);
        return UiCommandAck.accepted(commandId, sessionId(), "continue submitted");
    }

    private UiCommandAck cancelTurn(String commandId) {
        return session.cancelRunningTask()
                ? UiCommandAck.accepted(commandId, sessionId(), "cancel requested")
                : UiCommandAck.rejected(commandId, sessionId(), "no running turn to cancel");
    }

    private UiCommandAck setModel(UiCommand command, String commandId) {
        if (!(command.getPayload() instanceof UiCommandPayloads.SetModel input)
                || input.modelId() == null
                || input.modelId().isBlank()) {
            return UiCommandAck.rejected(commandId, sessionId(), "model/set requires modelId.");
        }
        if (session.status() == SessionStatus.RUNNING) {
            return UiCommandAck.rejected(commandId, sessionId(), "'/model' is disabled while a task is in progress.");
        }
        ModelSelection selection = sessionFactory.resolveModelSelection(
                input.providerId(),
                input.modelId(),
                input.reasoningEffort()
        );
        boolean changed = session.setActiveModelSelection(selection);
        String label = modelLabel(selection);
        if (!changed) {
            return UiCommandAck.accepted(commandId, sessionId(), "Kept model as " + label);
        }
        return UiCommandAck.accepted(commandId, sessionId(), "Set model to " + label);
    }

    public synchronized UiModelCatalog modelCatalog() {
        ModelSelection current = session == null ? null : session.activeModelSelection();
        return new UiModelCatalog(
                uiModelSelection(current),
                sessionFactory.modelOptions()
                        .stream()
                        .map(option -> uiModelInfo(option, current))
                        .toList(),
                sessionFactory.reasoningEfforts()
        );
    }

    private SessionOptions newSessionOptions(UiCommand command) {
        if (command.getPayload() instanceof UiCommandPayloads.NewSession input
                && input.cwd() != null
                && !input.cwd().isBlank()) {
            return SessionOptions.cwd(Path.of(input.cwd()));
        }
        return defaultSessionOptions;
    }

    private UiCommandAck approvalResponse(UiCommand command, String commandId) {
        if (!(command.getPayload() instanceof UiCommandPayloads.ApprovalResponse response)) {
            return UiCommandAck.rejected(commandId, sessionId(), "Approval response payload is required.");
        }
        boolean resolved = approvalCoordinator.resolve(response.approvalId(), response.approved(), response.reason());
        return resolved
                ? UiCommandAck.accepted(commandId, sessionId(), "approval resolved")
                : UiCommandAck.rejected(commandId, sessionId(), "approval request is not pending: " + response.approvalId());
    }

    private void switchSession(Session nextSession) {
        closeCurrentSession();
        session = configure(nextSession);
    }

    private Session configure(Session nextSession) {
        if (nextSession == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (eventSink != null) {
            nextSession.subscribe(eventSink);
        }
        nextSession.setApprovalHandler(approvalCoordinator);
        return nextSession;
    }

    public synchronized io.github.lingjiuu.protocol.UiHistory history() {
        return UiHistoryState.fromEvents(sessionId(), session.timelineEvents());
    }

    private String commandId(UiCommand command) {
        if (command.getCommandId() != null && !command.getCommandId().isBlank()) {
            return command.getCommandId();
        }
        return UiCommand.newCommandId();
    }

    private void closeCurrentSession() {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private UiSessionSummary sessionSummary(String sessionId) {
        java.util.List<TranscriptRecord> records;
        try {
            records = sessionFactory.config().transcriptStore().read(sessionId);
        } catch (RuntimeException e) {
            return new UiSessionSummary(
                    sessionId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0
            );
        }
        SessionMetaItem meta = null;
        String name = null;
        String preview = null;
        long updatedAt = 0;
        for (TranscriptRecord record : records) {
            if (record == null) {
                continue;
            }
            updatedAt = Math.max(updatedAt, record.getTimestamp());
            if (meta == null && record.getItem() instanceof SessionMetaItem sessionMeta) {
                meta = sessionMeta;
            }
            if (record.getItem() instanceof SessionNameItem sessionName
                    && sessionName.getName() != null
                    && !sessionName.getName().isBlank()) {
                name = sessionName.getName();
            }
            if (preview == null && record.getItem() instanceof MessageTranscriptItem messageItem
                    && messageItem.getMessage() instanceof UserMessage userMessage) {
                preview = normalizeName(MessageContents.text(userMessage));
            }
        }
        return new UiSessionSummary(
                sessionId,
                name,
                preview,
                meta == null ? null : meta.getCreatedAt(),
                updatedAt == 0 ? null : updatedAt,
                meta == null ? null : meta.getCwd(),
                meta == null ? null : meta.getModelProvider(),
                meta == null ? null : meta.getModelId(),
                records.size()
        );
    }

    private String normalizeName(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UiModelInfo uiModelInfo(ModelOption option, ModelSelection current) {
        return new UiModelInfo(
                option.providerId(),
                option.modelId(),
                option.name(),
                option.api(),
                option.contextWindowTokens(),
                option.autoCompactTokenLimit(),
                option.input(),
                sameModel(option, current)
        );
    }

    private UiModelSelection uiModelSelection(ModelSelection selection) {
        LlmModel model = selection == null ? null : selection.model();
        return new UiModelSelection(
                model == null ? null : model.getProvider(),
                model == null ? null : model.getId(),
                model == null ? null : model.getName(),
                reasoningEffort(selection)
        );
    }

    private boolean sameModel(ModelOption option, ModelSelection selection) {
        LlmModel model = selection == null ? null : selection.model();
        return model != null
                && java.util.Objects.equals(option.providerId(), model.getProvider())
                && java.util.Objects.equals(option.modelId(), model.getId());
    }

    private String modelLabel(ModelSelection selection) {
        LlmModel model = selection == null ? null : selection.model();
        String provider = model == null ? null : model.getProvider();
        String id = model == null ? null : model.getId();
        String effort = reasoningEffort(selection);
        String label = (provider == null || provider.isBlank() ? "" : provider + "/") + (id == null ? "" : id);
        return effort == null ? label : label + " " + effort.toLowerCase();
    }

    private String reasoningEffort(ModelSelection selection) {
        ReasoningOptions reasoning = selection == null ? null : selection.reasoning();
        return reasoning == null || reasoning.getReasoningEffort() == null ? null : reasoning.getReasoningEffort().name();
    }
}
