package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.input.MaterializedInput;
import io.github.lingjiuu.llm.LlmClientSession;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.tool.ToolCancellationToken;

public class TaskContext {

    private final Session session;
    private final TurnContext turnContext;
    private final MaterializedInput materializedInput;
    private final ToolCancellationToken cancellationToken;
    private final LlmClientSession modelSession;
    private final ModelSelection modelSelection;
    private final SessionConfig sessionConfig;

    public TaskContext(
            Session session,
            TurnContext turnContext,
            MaterializedInput materializedInput,
            ToolCancellationToken cancellationToken,
            LlmClientSession modelSession,
            ModelSelection modelSelection,
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
        this.materializedInput = materializedInput;
        this.cancellationToken = cancellationToken == null ? ToolCancellationToken.none() : cancellationToken;
        if (modelSession == null) {
            throw new IllegalArgumentException("modelSession must not be null");
        }
        this.modelSession = modelSession;
        if (modelSelection == null) {
            throw new IllegalArgumentException("model selection must not be null");
        }
        this.modelSelection = modelSelection;
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

    public int turn() {
        return turnContext.turn();
    }

    public MaterializedInput materializedInput() {
        return materializedInput;
    }

    public ToolCancellationToken cancellationToken() {
        return cancellationToken;
    }

    public LlmClientSession modelSession() {
        return modelSession;
    }

    public ModelSelection modelSelection() {
        return modelSelection;
    }

    public SessionConfig sessionConfig() {
        return sessionConfig;
    }

    public boolean isCancelled() {
        return cancellationToken.isCancellationRequested();
    }
}
