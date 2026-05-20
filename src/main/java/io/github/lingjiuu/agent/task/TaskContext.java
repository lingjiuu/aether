package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.input.MaterializedInput;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.tool.ToolCancellationToken;

public class TaskContext {

    private final Session session;
    private final TurnContext turnContext;
    private final MaterializedInput materializedInput;
    private final ToolCancellationToken cancellationToken;

    public TaskContext(
            Session session,
            TurnContext turnContext,
            MaterializedInput materializedInput,
            ToolCancellationToken cancellationToken
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

    public boolean isCancelled() {
        return cancellationToken.isCancellationRequested();
    }
}
