package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.agent.turn.TurnContext;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class ToolScope implements AutoCloseable {

    private static final long ABORT_DRAIN_TIMEOUT_MILLIS = 250;
    private static final double MIN_REPORTED_ABORT_SECONDS = 0.1;

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
        long startedAtNanos = System.nanoTime();
        Future<ToolOutcome> future = executor.submit(() -> runToolCall(assistantMessage, toolCall, startedAtNanos));
        tasks.add(new ToolTask(toolCall, future, startedAtNanos));
    }

    int size() {
        return tasks.size();
    }

    List<ToolOutcome> drain() {
        List<ToolOutcome> outcomes = new ArrayList<>();
        for (ToolTask task : tasks) {
            if (context.isCancelled()) {
                task.future().cancel(true);
                outcomes.add(abortedOutcome(task));
                continue;
            }
            try {
                outcomes.add(task.future().get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close();
                outcomes.add(abortedOutcome(task));
                appendAbortedOutcomesAfter(outcomes, task);
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

    private void appendAbortedOutcomesAfter(List<ToolOutcome> outcomes, ToolTask currentTask) {
        boolean append = false;
        for (ToolTask task : tasks) {
            if (append) {
                outcomes.add(abortedOutcome(task));
            } else if (task == currentTask) {
                append = true;
            }
        }
    }

    List<ToolOutcome> abortAndDrain() {
        List<ToolOutcome> outcomes = new ArrayList<>();
        for (ToolTask task : tasks) {
            if (!task.future().isDone()) {
                task.future().cancel(true);
            }
            outcomes.add(outcomeOrAborted(task));
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

    private ToolOutcome runToolCall(
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            long startedAtNanos
    ) {
        if (context.isCancelled()) {
            return abortedOutcome(toolCall, startedAtNanos);
        }
        ToolRunResult prepared = session.toolRunner().prepare(
                assistantMessage,
                toolCall,
                activeToolNames,
                context.cancellationToken(),
                null,
                partialResult -> session.events().toolExecutionUpdate(toolCall, partialResult, turnContext)
        );
        if (!prepared.ready()) {
            return new ToolOutcome(toolCall, prepared.failureResult());
        }
        if (context.isCancelled()) {
            return abortedOutcome(toolCall, startedAtNanos);
        }

        Lock executionLock = prepared.definition().supportsParallelToolCalls()
                ? parallelLock.readLock()
                : parallelLock.writeLock();
        try {
            executionLock.lockInterruptibly();
            try {
                return new ToolOutcome(toolCall, runPreparedToolCall(prepared, startedAtNanos));
            } finally {
                executionLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (context.isCancelled()) {
                return abortedOutcome(toolCall, startedAtNanos);
            }
            return new ToolOutcome(toolCall, ToolExecutionResult.errorText("Tool execution interrupted."));
        }
    }

    private ToolExecutionResult runPreparedToolCall(ToolRunResult prepared, long startedAtNanos) {
        if (context.isCancelled()) {
            return abortedResult(prepared.context().getToolCall(), elapsedSeconds(startedAtNanos));
        }
        PermissionDecision decision = session.permissionManager().decide(prepared.invocation(), prepared.context());
        if (decision == null || decision.allowed()) {
            return session.toolRunner().run(prepared);
        }

        if (decision.mode() == PermissionMode.ASK) {
            ApprovalResponse response = session.requestApproval(approvalRequest(prepared, decision), turnContext);
            if (response != null && response.approved()) {
                if (context.isCancelled()) {
                    return abortedResult(prepared.context().getToolCall(), elapsedSeconds(startedAtNanos));
                }
                return session.toolRunner().run(prepared);
            }
            String reason = response == null || response.reason() == null || response.reason().isBlank()
                    ? "Tool permission was not approved."
                    : response.reason();
            String message = "Tool permission denied: " + reason;
            session.events().error(turnContext, message);
            return ToolExecutionResult.errorText(message);
        }

        String reason = decision.reason() == null || decision.reason().isBlank()
                ? "Tool permission was not granted."
                : decision.reason();
        String prefix = "Tool permission denied: ";
        session.events().error(turnContext, prefix + reason);
        return ToolExecutionResult.errorText(prefix + reason);
    }

    private ToolOutcome outcomeOrAborted(ToolTask task) {
        if (!task.future().isDone()) {
            return abortedOutcome(task);
        }
        try {
            ToolOutcome outcome = task.future().get(ABORT_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return outcome == null ? abortedOutcome(task) : outcome;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return abortedOutcome(task);
        } catch (Exception e) {
            return abortedOutcome(task);
        }
    }

    private ToolOutcome abortedOutcome(ToolCallContent toolCall) {
        return abortedOutcome(toolCall, System.nanoTime());
    }

    private ToolOutcome abortedOutcome(ToolTask task) {
        return new ToolOutcome(task.toolCall(), abortedResult(task.toolCall(), elapsedSeconds(task)));
    }

    private ToolOutcome abortedOutcome(ToolCallContent toolCall, long startedAtNanos) {
        return new ToolOutcome(toolCall, abortedResult(toolCall, elapsedSeconds(startedAtNanos)));
    }

    private ToolExecutionResult abortedResult(ToolCallContent toolCall, double elapsedSeconds) {
        return ToolExecutionResult.errorText(abortMessage(toolCall, elapsedSeconds));
    }

    private String abortMessage(ToolCallContent toolCall, double elapsedSeconds) {
        if (toolCall != null && "bash".equals(toolCall.getToolName())) {
            return String.format("Wall time: %.1f seconds\naborted by user", elapsedSeconds);
        }
        return genericAbortMessage(elapsedSeconds);
    }

    private String genericAbortMessage(double elapsedSeconds) {
        return String.format("aborted by user after %.1fs", elapsedSeconds);
    }

    private double elapsedSeconds(ToolTask task) {
        return elapsedSeconds(task.startedAtNanos());
    }

    private double elapsedSeconds(long startedAtNanos) {
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedAtNanos);
        double seconds = elapsedNanos / 1_000_000_000.0;
        return Math.max(MIN_REPORTED_ABORT_SECONDS, seconds);
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
        session.events().toolCall(toolCall, turnContext);
        session.events().toolExecutionStarted(toolCall, turnContext);
    }

    private String exceptionMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "unknown error";
        }
        return throwable.getMessage();
    }

    private record ToolTask(ToolCallContent toolCall, Future<ToolOutcome> future, long startedAtNanos) {
    }
}

record ToolOutcome(ToolCallContent toolCall, ToolExecutionResult executionResult) {
}
