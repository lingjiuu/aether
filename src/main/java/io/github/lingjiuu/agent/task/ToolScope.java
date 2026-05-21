package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.event.UiEvent;
import io.github.lingjiuu.event.UiEventType;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRunResult;
import io.github.lingjiuu.tool.permission.ApprovalId;
import io.github.lingjiuu.tool.permission.ApprovalRequest;
import io.github.lingjiuu.tool.permission.ApprovalResponse;
import io.github.lingjiuu.tool.permission.PermissionDecision;
import io.github.lingjiuu.tool.permission.PermissionMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class ToolScope implements AutoCloseable {

    private final Session session;
    private final TaskContext context;
    private final TurnContext turnContext;
    private final List<String> activeToolNames;
    private final ExecutorService executor;
    private final ReentrantReadWriteLock parallelLock = new ReentrantReadWriteLock(true);
    private final List<ToolTask> tasks = new ArrayList<>();
    private boolean closed;

    private ToolScope(Session session, TaskContext context, TurnContext turnContext) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("task context must not be null");
        }
        if (turnContext == null) {
            throw new IllegalArgumentException("turn context must not be null");
        }
        this.session = session;
        this.context = context;
        this.turnContext = turnContext;
        this.activeToolNames = session.activeToolNames();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    static ToolScope open(Session session, TaskContext context, TurnContext turnContext) {
        return new ToolScope(session, context, turnContext);
    }

    void fork(AssistantMessage assistantMessage, ToolCallContent toolCall) {
        if (closed || context.isCancelled() || toolCall == null) {
            return;
        }
        emitToolCallStarted(toolCall);
        Future<ToolOutcome> future = executor.submit(() -> runToolCall(assistantMessage, toolCall));
        tasks.add(new ToolTask(toolCall, future));
    }

    int size() {
        return tasks.size();
    }

    List<ToolOutcome> drain() {
        List<ToolOutcome> outcomes = new ArrayList<>();
        for (ToolTask task : tasks) {
            if (context.isCancelled()) {
                break;
            }
            try {
                outcomes.add(task.future().get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close();
                break;
            } catch (ExecutionException e) {
                outcomes.add(new ToolOutcome(
                        task.toolCall(),
                        ToolExecutionResult.errorText("Tool execution failed: " + exceptionMessage(e.getCause()))
                ));
            }
        }
        return List.copyOf(outcomes);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (ToolTask task : tasks) {
            if (!task.future().isDone()) {
                task.future().cancel(true);
            }
        }
        executor.shutdownNow();
    }

    private ToolOutcome runToolCall(AssistantMessage assistantMessage, ToolCallContent toolCall) {
        ToolRunResult prepared = session.toolRunner().prepare(
                assistantMessage,
                toolCall,
                activeToolNames,
                context.cancellationToken(),
                null,
                partialResult -> session.emit(UiEvent.builder()
                        .type(UiEventType.TOOL_EXECUTION_UPDATE)
                        .sessionId(turnContext.sessionId())
                        .turn(turnContext.turn())
                        .toolCall(toolCall)
                        .partialToolResult(partialResult)
                        .build())
        );
        if (!prepared.ready()) {
            return new ToolOutcome(toolCall, prepared.failureResult());
        }

        Lock executionLock = prepared.definition().supportsParallelToolCalls()
                ? parallelLock.readLock()
                : parallelLock.writeLock();
        try {
            executionLock.lockInterruptibly();
            try {
                return new ToolOutcome(toolCall, runPreparedToolCall(prepared));
            } finally {
                executionLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolOutcome(toolCall, ToolExecutionResult.errorText("Tool execution interrupted."));
        }
    }

    private ToolExecutionResult runPreparedToolCall(ToolRunResult prepared) {
        PermissionDecision decision = session.permissionManager().decide(prepared.invocation(), prepared.context());
        if (decision == null || decision.allowed()) {
            return session.toolRunner().run(prepared);
        }

        if (decision.mode() == PermissionMode.ASK) {
            ApprovalResponse response = session.requestApproval(approvalRequest(prepared, decision), turnContext);
            if (response != null && response.approved()) {
                return session.toolRunner().run(prepared);
            }
            String reason = response == null || response.reason() == null || response.reason().isBlank()
                    ? "Tool permission was not approved."
                    : response.reason();
            String message = "Tool permission denied: " + reason;
            session.emitError(turnContext, message);
            return ToolExecutionResult.errorText(message);
        }

        String reason = decision.reason() == null || decision.reason().isBlank()
                ? "Tool permission was not granted."
                : decision.reason();
        String prefix = "Tool permission denied: ";
        session.emitError(turnContext, prefix + reason);
        return ToolExecutionResult.errorText(prefix + reason);
    }

    private ApprovalRequest approvalRequest(ToolRunResult prepared, PermissionDecision decision) {
        return new ApprovalRequest(
                ApprovalId.create(),
                prepared.invocation().definition().name(),
                prepared.context().getToolCallId(),
                prepared.invocation().definition().riskLevel(),
                prepared.context().getArguments(),
                decision.reason()
        );
    }

    private void emitToolCallStarted(ToolCallContent toolCall) {
        session.emit(UiEvent.builder()
                .type(UiEventType.TOOL_CALL)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .toolCall(toolCall)
                .build());
        session.emit(UiEvent.builder()
                .type(UiEventType.TOOL_EXECUTION_STARTED)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .toolCall(toolCall)
                .build());
    }

    private String exceptionMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "unknown error";
        }
        return throwable.getMessage();
    }

    private record ToolTask(ToolCallContent toolCall, Future<ToolOutcome> future) {
    }
}

record ToolOutcome(ToolCallContent toolCall, ToolExecutionResult executionResult) {
}
