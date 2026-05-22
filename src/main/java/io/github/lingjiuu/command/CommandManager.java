package io.github.lingjiuu.command;

import io.github.lingjiuu.event.EventSink;
import io.github.lingjiuu.protocol.UiCommand;
import io.github.lingjiuu.protocol.UiCommandAck;
import io.github.lingjiuu.protocol.UiCommandPayloads;
import io.github.lingjiuu.protocol.UiCommandType;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.tool.permission.ApprovalHandler;
import io.github.lingjiuu.ui.approval.ApprovalCoordinator;
import io.github.lingjiuu.ui.history.UiHistoryState;

public class CommandManager implements AutoCloseable {

    private final SessionFactory sessionFactory;
    private final EventSink eventSink;
    private final ApprovalCoordinator approvalCoordinator;
    private Session session;

    public CommandManager(
            SessionFactory sessionFactory,
            EventSink eventSink,
            ApprovalHandler approvalHandler
    ) {
        if (sessionFactory == null) {
            throw new IllegalArgumentException("sessionFactory must not be null");
        }
        this.sessionFactory = sessionFactory;
        this.eventSink = eventSink;
        this.approvalCoordinator = new ApprovalCoordinator(approvalHandler);
        this.session = configure(sessionFactory.openSession());
    }

    public synchronized UiCommandAck handle(UiCommand command) {
        if (command == null || command.getType() == null) {
            return UiCommandAck.rejected(sessionId(), "Command type is required.");
        }
        String commandId = commandId(command);
        try {
            return switch (command.getType()) {
                case SUBMIT_USER_INPUT -> submit(command, commandId);
                case NEW_SESSION -> newSession(commandId);
                case RESUME_SESSION -> resume(command, commandId);
                case COMPACT -> compact(commandId);
                case CONTINUE -> continueSession(commandId);
                case CANCEL_TURN -> cancelTurn(commandId);
                case APPROVAL_RESPONSE -> approvalResponse(command, commandId);
                case RELOAD_SKILLS -> reloadSkills(commandId);
            };
        } catch (RuntimeException e) {
            return UiCommandAck.rejected(commandId, sessionId(), e.getMessage());
        }
    }

    public synchronized Session currentSession() {
        return session;
    }

    public synchronized String sessionId() {
        return session == null ? null : session.sessionId();
    }

    @Override
    public synchronized void close() {
        closeCurrentSession();
    }

    private UiCommandAck submit(UiCommand command, String commandId) {
        if (!(command.getPayload() instanceof UiCommandPayloads.SubmitUserInput input)
                || input.text() == null
                || input.text().isBlank()) {
            return UiCommandAck.rejected(commandId, sessionId(), "Input text is required.");
        }
        session.submitAsync(io.github.lingjiuu.input.TurnInput.ofText(input.text().trim()), commandId);
        return UiCommandAck.accepted(commandId, sessionId(), "submitted");
    }

    private UiCommandAck newSession(String commandId) {
        switchSession(sessionFactory.openSession());
        return UiCommandAck.accepted(commandId, sessionId(), history(), "new session");
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

    private UiCommandAck reloadSkills(String commandId) {
        session.reloadSkills();
        return UiCommandAck.accepted(commandId, sessionId(), "skills reloaded");
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
}
