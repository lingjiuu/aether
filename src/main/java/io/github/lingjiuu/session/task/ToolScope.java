package io.github.lingjiuu.session.task;

import io.github.lingjiuu.event.UiEvents;
import io.github.lingjiuu.session.turn.TurnContext;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolCallResult;
import io.github.lingjiuu.tool.ToolCallStatus;
import io.github.lingjiuu.tool.ToolFailure;
import io.github.lingjiuu.tool.ToolRouter;
import io.github.lingjiuu.trace.TraceSpan;
import io.github.lingjiuu.tool.permission.ApprovalId;
import io.github.lingjiuu.tool.permission.ApprovalRequest;
import io.github.lingjiuu.tool.permission.ApprovalResponse;
import io.github.lingjiuu.tool.permission.PermissionDecision;

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

    void fork(ToolCallRef toolCallRef) {
        if (closed || context.isCancelled() || toolCallRef == null || toolCallRef.toolCall() == null) {
            return;
        }
        emitToolCallScheduled(toolCallRef);
        long startedAtNanos = System.nanoTime();
        Future<ToolOutcome> future = executor.submit(() -> runToolCall(toolCallRef, startedAtNanos));
        tasks.add(new ToolTask(toolCallRef, future, startedAtNanos));
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
                        task.toolCallRef(),
                        null,
                        null,
                        ToolCallResult.failure(ToolFailure.runtime("Tool execution failed: " + exceptionMessage(e.getCause()))),
                        "FAILED",
                        elapsedMillis(task.startedAtNanos()),
                        null
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

    private ToolOutcome runToolCall(ToolCallRef toolCallRef, long startedAtNanos) {
        ToolCallContent toolCall = toolCallRef.toolCall();
        TraceSpan traceSpan = session.config().traceRecorder().startToolSpan(context.traceContext(), toolCall);
        if (context.isCancelled()) {
            return finishTracedOutcome(traceSpan, abortedOutcome(toolCallRef, startedAtNanos));
        }
        try {
            ToolRouter.PreparedToolCall prepared = session.toolRouter().buildInvocation(
                    toolCall,
                    activeToolNames,
                    context.cancellationToken(),
                    null,
                    session.readFileState(),
                    (tool, partialResult) -> session.events().emit(UiEvents.toolExecutionUpdate(
                            toolCallRef.itemId(),
                            toolCallRef.contentIndex(),
                            toolCall,
                            tool,
                            partialResult,
                            elapsedMillis(startedAtNanos),
                            turnContext
                    ))
            );
            if (!prepared.ready()) {
                return finishTracedOutcome(traceSpan, failedOutcome(toolCallRef, prepared, prepared.failureResult(), startedAtNanos));
            }
            if (context.isCancelled()) {
                return finishTracedOutcome(traceSpan, abortedOutcome(toolCallRef, startedAtNanos));
            }

            Lock executionLock = prepared.tool().supportsParallelToolCalls()
                    ? parallelLock.readLock()
                    : parallelLock.writeLock();
            try {
                executionLock.lockInterruptibly();
                try {
                    emitToolExecutionBegin(toolCallRef, prepared.tool());
                    ToolOutcome outcome = runPreparedToolCall(toolCallRef, prepared, startedAtNanos);
                    if (context.isCancelled()) {
                        return finishTracedOutcome(traceSpan, abortedOutcome(toolCallRef, startedAtNanos));
                    }
                    return finishTracedOutcome(traceSpan, outcome);
                } finally {
                    executionLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (context.isCancelled()) {
                    return finishTracedOutcome(traceSpan, abortedOutcome(toolCallRef, startedAtNanos));
                }
                return finishTracedOutcome(traceSpan, failedOutcome(
                        toolCallRef,
                        prepared,
                        ToolCallResult.failure(ToolFailure.runtime("Tool execution interrupted.")),
                        startedAtNanos
                ));
            }
        } catch (RuntimeException e) {
            traceSpan.fail(e);
            throw e;
        }
    }

    private ToolOutcome finishTracedOutcome(TraceSpan traceSpan, ToolOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        session.config().traceRecorder().recordToolExecutionOutput(
                traceSpan,
                outcome.callResult(),
                outcome.status(),
                outcome.durationMs()
        );
        return outcome.withTraceSpanId(traceSpan == null ? null : traceSpan.id());
    }

    private ToolOutcome runPreparedToolCall(
            ToolCallRef toolCallRef,
            ToolRouter.PreparedToolCall prepared,
            long startedAtNanos
    ) {
        if (context.isCancelled()) {
            return abortedOutcome(toolCallRef, startedAtNanos);
        }
        PermissionDecision decision = session.permissionManager().decide(
                prepared.tool(),
                prepared.context().toolName(),
                prepared.permissionArguments()
        );
        if (decision == null || decision.allowed()) {
            return completedOutcome(toolCallRef, prepared, session.toolRouter().dispatch(prepared), startedAtNanos);
        }

        if (decision.action() == PermissionDecision.Action.ASK) {
            ApprovalResponse response = session.requestApproval(approvalRequest(prepared, decision), turnContext);
            if (response != null && response.approved()) {
                if (context.isCancelled()) {
                    return abortedOutcome(toolCallRef, startedAtNanos);
                }
                return completedOutcome(toolCallRef, prepared, session.toolRouter().dispatch(prepared), startedAtNanos);
            }
            String reason = response == null || response.reason() == null || response.reason().isBlank()
                    ? "Tool permission was not approved."
                    : response.reason();
            return declinedOutcome(toolCallRef, "Tool permission denied: " + reason, startedAtNanos);
        }

        String reason = decision.reason() == null || decision.reason().isBlank()
                ? "Tool permission was not granted."
                : decision.reason();
        return declinedOutcome(toolCallRef, "Tool permission denied: " + reason, startedAtNanos);
    }

    private void emitToolExecutionBegin(ToolCallRef toolCallRef, Tool toolDefinition) {
        session.events().emit(UiEvents.toolExecutionBegin(
                toolCallRef.itemId(),
                toolCallRef.contentIndex(),
                toolCallRef.toolCall(),
                toolDefinition,
                turnContext
        ));
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

    private ToolOutcome abortedOutcome(ToolTask task) {
        return new ToolOutcome(
                task.toolCallRef(),
                null,
                null,
                abortedResult(task.toolCallRef().toolCall(), elapsedSeconds(task)),
                "ABORTED",
                elapsedMillis(task.startedAtNanos()),
                null
        );
    }

    private ToolOutcome abortedOutcome(ToolCallRef toolCallRef, long startedAtNanos) {
        return new ToolOutcome(
                toolCallRef,
                null,
                null,
                abortedResult(toolCallRef.toolCall(), elapsedSeconds(startedAtNanos)),
                "ABORTED",
                elapsedMillis(startedAtNanos),
                null
        );
    }

    private ToolOutcome completedOutcome(
            ToolCallRef toolCallRef,
            ToolRouter.PreparedToolCall prepared,
            ToolCallResult<?> result,
            long startedAtNanos
    ) {
        ToolCallResult<?> safeResult = result == null
                ? ToolCallResult.failure(ToolFailure.runtime("Tool returned no result."))
                : result;
        return new ToolOutcome(
                toolCallRef,
                prepared == null ? null : prepared.tool(),
                prepared == null ? null : prepared.input(),
                safeResult,
                statusName(safeResult.status()),
                elapsedMillis(startedAtNanos),
                null
        );
    }

    private ToolOutcome failedOutcome(
            ToolCallRef toolCallRef,
            ToolRouter.PreparedToolCall prepared,
            ToolCallResult<?> result,
            long startedAtNanos
    ) {
        ToolCallResult<?> safeResult = result == null
                ? ToolCallResult.failure(ToolFailure.runtime("Tool execution failed."))
                : result;
        return new ToolOutcome(
                toolCallRef,
                prepared == null ? null : prepared.tool(),
                prepared == null ? null : prepared.input(),
                safeResult,
                statusName(safeResult.status()),
                elapsedMillis(startedAtNanos),
                null
        );
    }

    private ToolOutcome declinedOutcome(ToolCallRef toolCallRef, String message, long startedAtNanos) {
        return new ToolOutcome(
                toolCallRef,
                null,
                null,
                ToolCallResult.failure(ToolFailure.permission(message), ToolCallStatus.DECLINED),
                "DECLINED",
                elapsedMillis(startedAtNanos),
                null
        );
    }

    private ToolCallResult<?> abortedResult(ToolCallContent toolCall, double elapsedSeconds) {
        return ToolCallResult.failure(
                ToolFailure.cancellation(abortMessage(toolCall, elapsedSeconds)),
                ToolCallStatus.ABORTED
        );
    }

    private String abortMessage(ToolCallContent toolCall, double elapsedSeconds) {
        if (toolCall != null && ("Bash".equals(toolCall.getToolName()) || "bash".equals(toolCall.getToolName()) || "powershell".equals(toolCall.getToolName()))) {
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

    private long elapsedMillis(long startedAtNanos) {
        long elapsedNanos = Math.max(0L, System.nanoTime() - startedAtNanos);
        return TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    }

    private ApprovalRequest approvalRequest(ToolRouter.PreparedToolCall prepared, PermissionDecision decision) {
        return new ApprovalRequest(
                ApprovalId.create(),
                prepared.tool().name(),
                prepared.context().toolCallId(),
                prepared.tool().riskLevel(),
                prepared.permissionArguments(),
                decision.reason()
        );
    }

    private String statusName(ToolCallStatus status) {
        return status == null ? ToolCallStatus.COMPLETED.name() : status.name();
    }

    private void emitToolCallScheduled(ToolCallRef toolCallRef) {
        Tool tool = session.toolRegistry().findTool(toolCallRef.toolCall().getToolName());
        session.events().emit(UiEvents.toolCall(
                toolCallRef.itemId(),
                toolCallRef.contentIndex(),
                toolCallRef.toolCall(),
                tool,
                turnContext
        ));
    }

    private String exceptionMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "unknown error";
        }
        return throwable.getMessage();
    }

    private record ToolTask(ToolCallRef toolCallRef, Future<ToolOutcome> future, long startedAtNanos) {
    }
}

record ToolCallRef(String itemId, Integer contentIndex, ToolCallContent toolCall) {
}

record ToolOutcome(
        ToolCallRef toolCallRef,
        Tool<?, ?> tool,
        Object input,
        ToolCallResult<?> callResult,
        String status,
        Long durationMs,
        String traceSpanId
) {
    ToolOutcome withTraceSpanId(String traceSpanId) {
        return new ToolOutcome(toolCallRef, tool, input, callResult, status, durationMs, traceSpanId);
    }
}
