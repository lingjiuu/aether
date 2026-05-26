package io.github.lingjiuu.session.task;

import io.github.lingjiuu.session.turn.TurnContext;
import io.github.lingjiuu.input.ProcessedTurnInput;
import io.github.lingjiuu.model.client.ModelClientSession;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.tool.ToolCancellationToken;

public class TaskContext {

    private final Session session;
    private final TurnContext turnContext;
    private final ProcessedTurnInput processedInput;
    private final ToolCancellationToken cancellationToken;
    private final ModelClientSession modelSession;
    private final SessionConfig sessionConfig;

    public TaskContext(
            Session session,
            TurnContext turnContext,
            ProcessedTurnInput processedInput,
            ToolCancellationToken cancellationToken,
            ModelClientSession modelSession,
            SessionConfig sessionConfig
    ) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (turnContext == null) {
            throw new IllegalArgumentException("turn context must not be null");
        }
        this.session = session;
        this.turnContext = turnContext;
        this.processedInput = processedInput;
        this.cancellationToken = cancellationToken == null ? ToolCancellationToken.none() : cancellationToken;
        if (modelSession == null) {
            throw new IllegalArgumentException("modelSession must not be null");
        }
        this.modelSession = modelSession;
        if (sessionConfig == null) {
            throw new IllegalArgumentException("session config must not be null");
        }
        this.sessionConfig = sessionConfig;
    }

    public Session session() {
        return session;
    }

    public TurnContext turnContext() {
        return turnContext;
    }

    public ProcessedTurnInput processedInput() {
        return processedInput;
    }

    public ToolCancellationToken cancellationToken() {
        return cancellationToken;
    }

    public ModelClientSession modelSession() {
        return modelSession;
    }

    public SessionConfig sessionConfig() {
        return sessionConfig;
    }

    public boolean isCancelled() {
        return cancellationToken.isCancellationRequested();
    }
}
