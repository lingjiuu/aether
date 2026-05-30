package io.github.lingjiuu.session.task;

import io.github.lingjiuu.event.UiEvents;
import io.github.lingjiuu.session.turn.TurnContext;
import io.github.lingjiuu.protocol.UiItemKind;
import io.github.lingjiuu.input.ProcessedTurnInput;
import io.github.lingjiuu.model.client.AssistantStreamEvent;
import io.github.lingjiuu.model.client.ModelErrorInfo;
import io.github.lingjiuu.model.client.ModelRequest;
import io.github.lingjiuu.model.client.ModelRetryOptions;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.result.ProcessedToolResult;
import io.github.lingjiuu.tool.result.ToolResultInput;
import io.github.lingjiuu.tool.result.ToolResultProcessor;
import io.github.lingjiuu.transcript.TranscriptRecord;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RegularTask implements SessionTask {

    private static final long MIN_EFFECTIVE_COMPACT_TOKEN_DROP = 512;
    private static final int MAX_CONTEXT_WINDOW_RECOVERIES = 2;

    @Override
    public TaskKind kind() {
        return TaskKind.REGULAR;
    }

    @Override
    public void run(TaskContext context) {
        Session session = context.session();
        TurnContext turnContext = context.turnContext();

        CompactTask compactTask = new CompactTask();
        long blockedAutoCompactAtOrBelow = -1;
        int contextWindowRecoveries = 0;
        session.applyToolResultBudgetForModel(turnContext);
        blockedAutoCompactAtOrBelow = runAutoCompactIfNeeded(
                session,
                context,
                compactTask,
                "pre-turn",
                blockedAutoCompactAtOrBelow
        );
        if (context.isCancelled()) {
            return;
        }

        session.recordInitialContextIfChanged(turnContext);
        SessionConfig turnConfig = context.sessionConfig();
        ModelRetryOptions retryOptions = turnConfig.endpoint() == null
                ? ModelRetryOptions.defaults()
                : turnConfig.endpoint().retryOptions();
        recordProcessedInput(session, context, turnContext);

        int streamRecoveries = 0;
        while (!context.isCancelled()) {
            session.applyToolResultBudgetForModel(turnContext);
            blockedAutoCompactAtOrBelow = runAutoCompactIfNeeded(
                    session,
                    context,
                    compactTask,
                    "pre-sampling",
                    blockedAutoCompactAtOrBelow
            );
            if (context.isCancelled()) {
                return;
            }

            List<Message> messages = session.contextManager()
                    .normalizeMessagesForModel(turnConfig.model().getInput());
            ModelRequest request = ModelRequest.from(
                    turnConfig.baseInstructions(),
                    turnConfig.reasoning(),
                    messages,
                    session.activeTools()
            );
            ToolOutcomeCoordinator toolOutcomes = new ToolOutcomeCoordinator(session, context, turnContext);
            try (ToolScope toolScope = ToolScope.open(session, context, turnContext, toolOutcomes::emitUi)) {
                AssistantMessage assistantMessage = session.sampleModelItems(
                        context.modelSession(),
                        request,
                        turnContext,
                        context.cancellationToken(),
                        context.traceContext(),
                        event -> handleStreamItem(session, context, turnContext, toolScope, event)
                );
                if (context.isCancelled() || assistantMessage.isAborted()) {
                    toolScope.abortAndDrainOrdered(toolOutcomes::record);
                    return;
                }
                if (assistantMessage.isError()) {
                    toolScope.drainOrdered(toolOutcomes::record);
                    session.applyToolResultBudgetForModel(turnContext);
                    if (assistantMessage.isContextWindowExceeded()
                            && contextWindowRecoveries < MAX_CONTEXT_WINDOW_RECOVERIES) {
                        contextWindowRecoveries++;
                        session.markContextWindowFull(turnContext);
                        if (compactTask.runInlineAutoCompact(context, "context-window-exceeded")) {
                            blockedAutoCompactAtOrBelow = -1;
                            continue;
                        }
                    }
                    if (assistantMessage.isRetryableStreamFailure()
                            && streamRecoveries < retryOptions.streamMaxRetries()) {
                        streamRecoveries++;
                        session.events().emit(UiEvents.streamRetry(
                                turnContext,
                                streamRecoveries,
                                retryOptions.streamMaxRetries()
                        ));
                        if (!sleepForRetry(context, retryDelayMillis(retryOptions, streamRecoveries, assistantMessage.getErrorInfo()))) {
                            return;
                        }
                        continue;
                    }
                    TranscriptRecord record = session.recordConversationMessage(assistantMessage, turnContext);
                    session.config().traceRecorder().recordConversationItem(
                            context.traceContext(),
                            "assistant_error",
                            assistantMessage,
                            record
                    );
                    session.events().emit(UiEvents.error(turnContext, assistantMessage.getErrorMessage()));
                    return;
                }

                streamRecoveries = 0;
                if (toolScope.size() == 0) {
                    return;
                }

                if (context.isCancelled() || Thread.currentThread().isInterrupted()) {
                    toolScope.abortAndDrainOrdered(toolOutcomes::record);
                } else {
                    toolScope.drainOrdered(toolOutcomes::record);
                }
                if (context.isCancelled() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                session.applyToolResultBudgetForModel(turnContext);
            }
            blockedAutoCompactAtOrBelow = runAutoCompactIfNeeded(
                    session,
                    context,
                    compactTask,
                    "mid-turn",
                    blockedAutoCompactAtOrBelow
            );
        }
    }

    private long retryDelayMillis(
            ModelRetryOptions retryOptions,
            int attempt,
            ModelErrorInfo errorInfo
    ) {
        if (errorInfo != null && errorInfo.retryAfterMillis() != null) {
            return Math.min(errorInfo.retryAfterMillis(), retryOptions.maxDelayMillis());
        }
        return retryOptions.delayMillis(attempt);
    }

    private boolean sleepForRetry(TaskContext context, long delayMillis) {
        long remainingMillis = Math.max(1L, delayMillis);
        while (remainingMillis > 0L) {
            if (context.isCancelled()) {
                return false;
            }
            long chunk = Math.min(remainingMillis, 100L);
            long before = System.nanoTime();
            try {
                Thread.sleep(chunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            long elapsed = Math.max(1L, (System.nanoTime() - before) / 1_000_000L);
            remainingMillis -= elapsed;
        }
        return !context.isCancelled();
    }

    private void handleStreamItem(
            Session session,
            TaskContext context,
            TurnContext turnContext,
            ToolScope toolScope,
            AssistantStreamEvent event
    ) {
        if (event == null || event.getType() == null) {
            return;
        }

        switch (event.getType()) {
            case TEXT_START -> session.events().emit(UiEvents.itemStarted(
                    turnContext,
                    UiItemKind.ASSISTANT_TEXT,
                    event.getItemId(),
                    event.getContentIndex(),
                    null,
                    null
            ));
            case THINKING_START -> session.events().emit(UiEvents.itemStarted(
                    turnContext,
                    UiItemKind.REASONING,
                    event.getItemId(),
                    event.getContentIndex(),
                    null,
                    null
            ));
            case TOOLCALL_START -> session.events().emit(UiEvents.itemStarted(
                    turnContext,
                    UiItemKind.TOOL_CALL,
                    event.getItemId(),
                    event.getContentIndex(),
                    event.getToolCall(),
                    session.toolRegistry().findTool(event.getToolName())
            ));
            case TEXT_DELTA -> session.events().emit(UiEvents.assistantTextDelta(
                    turnContext,
                    event.getItemId(),
                    event.getContentIndex(),
                    event.getDelta()
            ));
            case THINKING_DELTA -> session.events().emit(UiEvents.reasoningDelta(
                    turnContext,
                    event.getItemId(),
                    event.getContentIndex(),
                    event.getDelta()
            ));
            case TOOLCALL_DELTA -> session.events().emit(UiEvents.toolArgumentsDelta(
                    turnContext,
                    event.getItemId(),
                    event.getContentIndex(),
                    event.getToolCall(),
                    session.toolRegistry().findTool(event.getToolName()),
                    event.getDelta()
            ));
            case TEXT_END -> completeTextItem(session, context, turnContext, event);
            case THINKING_END -> completeThinkingItem(session, context, turnContext, event);
            case TOOLCALL_END -> completeToolCallItemAndFork(session, context, turnContext, toolScope, event);
            case ERROR -> {
            }
            default -> {
            }
        }
    }

    private void completeTextItem(Session session, TaskContext context, TurnContext turnContext, AssistantStreamEvent event) {
        AssistantMessage assistantMessage = session.contextBuilder().assistantTextMessage(
                event.getPartial(),
                event.getContent(),
                event.getProviderState()
        );
        TranscriptRecord record = session.recordConversationMessage(assistantMessage, turnContext);
        session.config().traceRecorder().recordConversationItem(
                context.traceContext(),
                "assistant_text",
                assistantMessage,
                record
        );
        session.events().emit(UiEvents.itemCompleted(
                turnContext,
                UiItemKind.ASSISTANT_TEXT,
                event.getItemId(),
                event.getContentIndex(),
                null,
                null,
                event.getContent()
        ));
    }

    private void completeThinkingItem(Session session, TaskContext context, TurnContext turnContext, AssistantStreamEvent event) {
        AssistantMessage assistantMessage = session.contextBuilder().assistantThinkingMessage(
                event.getPartial(),
                event.getContent(),
                event.getProviderState()
        );
        TranscriptRecord record = session.recordConversationMessage(assistantMessage, turnContext);
        session.config().traceRecorder().recordConversationItem(
                context.traceContext(),
                "assistant_reasoning",
                assistantMessage,
                record
        );
        session.events().emit(UiEvents.itemCompleted(
                turnContext,
                UiItemKind.REASONING,
                event.getItemId(),
                event.getContentIndex(),
                null,
                null,
                event.getContent()
        ));
    }

    private void completeToolCallItemAndFork(
            Session session,
            TaskContext context,
            TurnContext turnContext,
            ToolScope toolScope,
            AssistantStreamEvent event
    ) {
        if (event.getToolCall() == null) {
            session.events().emit(UiEvents.error(turnContext, "Model emitted an incomplete tool call."));
            return;
        }

        AssistantMessage assistantMessage = session.contextBuilder().assistantToolCallMessage(
                event.getPartial(),
                event.getToolCall(),
                event.getProviderState()
        );
        TranscriptRecord record = session.recordConversationMessage(assistantMessage, turnContext);
        session.config().traceRecorder().recordConversationItem(
                context.traceContext(),
                "assistant_tool_call",
                assistantMessage,
                record
        );
        session.events().emit(UiEvents.toolArgumentsDone(
                turnContext,
                event.getItemId(),
                event.getContentIndex(),
                event.getToolCall(),
                session.toolRegistry().findTool(event.getToolName())
        ));
        session.events().emit(UiEvents.itemCompleted(
                turnContext,
                UiItemKind.TOOL_CALL,
                event.getItemId(),
                event.getContentIndex(),
                event.getToolCall(),
                session.toolRegistry().findTool(event.getToolName()),
                event.getToolCall().getArgumentsJson()
        ));
        toolScope.fork(new ToolCallRef(
                event.getItemId(),
                event.getContentIndex(),
                event.getToolCall()
        ));
    }

    private ProcessedToolOutcome processToolOutcome(
            Session session,
            ToolOutcome outcome
    ) {
        if (outcome == null) {
            return null;
        }
        ToolCallRef toolCallRef = outcome.toolCallRef();
        if (toolCallRef == null || toolCallRef.toolCall() == null) {
            return null;
        }
        Tool tool = outcome.tool() == null
                ? session.toolRegistry().findTool(toolCallRef.toolCall().getToolName())
                : outcome.tool();
        ProcessedToolResult processedResult = new ToolResultProcessor(
                session.contextBuilder(),
                session.toolArtifactStore()
        ).processForRecording(new ToolResultInput(
                toolCallRef.toolCall(),
                tool,
                outcome.input(),
                outcome.callResult(),
                outcome.status(),
                outcome.durationMs(),
                outcome.approvalWaitMs(),
                outcome.executionDurationMs()
        ));
        if (processedResult == null) {
            return null;
        }
        return new ProcessedToolOutcome(outcome, tool, processedResult);
    }

    private void emitToolOutcomeUi(
            Session session,
            TurnContext turnContext,
            ProcessedToolOutcome processedOutcome
    ) {
        if (processedOutcome == null || processedOutcome.processedResult() == null) {
            return;
        }
        ToolOutcome outcome = processedOutcome.outcome();
        ToolCallRef toolCallRef = outcome.toolCallRef();
        if (toolCallRef == null || toolCallRef.toolCall() == null) {
            return;
        }
        ToolResultMessage toolResult = processedOutcome.processedResult().message();
        session.events().emit(UiEvents.toolExecutionEnd(
                toolCallRef.itemId(),
                toolCallRef.contentIndex(),
                toolCallRef.toolCall(),
                processedOutcome.tool(),
                toolResult,
                outcome.status(),
                outcome.durationMs(),
                outcome.approvalWaitMs(),
                outcome.executionDurationMs(),
                turnContext
        ));
        session.events().emit(UiEvents.toolResult(
                toolCallRef.itemId(),
                toolCallRef.contentIndex(),
                toolCallRef.toolCall(),
                toolResult,
                outcome.status(),
                outcome.durationMs(),
                outcome.approvalWaitMs(),
                outcome.executionDurationMs(),
                turnContext
        ));
    }

    private void recordToolOutcomeForModel(
            Session session,
            TaskContext context,
            TurnContext turnContext,
            ProcessedToolOutcome processedOutcome
    ) {
        if (processedOutcome == null || processedOutcome.processedResult() == null) {
            return;
        }
        ToolOutcome outcome = processedOutcome.outcome();
        ToolCallRef toolCallRef = outcome.toolCallRef();
        if (toolCallRef == null || toolCallRef.toolCall() == null) {
            return;
        }
        ToolResultMessage toolResult = processedOutcome.processedResult().message();
        TranscriptRecord record = session.recordConversationMessage(toolResult, turnContext);
        session.config().traceRecorder().recordToolResult(
                context.traceContext(),
                outcome.traceSpanId(),
                toolResult,
                record,
                processedOutcome.processedResult().artifactRefs(),
                outcome.status(),
                outcome.durationMs(),
                outcome.approvalWaitMs(),
                outcome.executionDurationMs()
        );
    }

    private final class ToolOutcomeCoordinator {
        private final Session session;
        private final TaskContext context;
        private final TurnContext turnContext;
        private final Map<Integer, ProcessedToolOutcome> processedByOrder = new HashMap<>();
        private final Set<Integer> uiEmitted = new HashSet<>();
        private final Set<Integer> recorded = new HashSet<>();

        private ToolOutcomeCoordinator(Session session, TaskContext context, TurnContext turnContext) {
            this.session = session;
            this.context = context;
            this.turnContext = turnContext;
        }

        synchronized void emitUi(ToolOutcome outcome) {
            ProcessedToolOutcome processed = processedOutcome(outcome);
            emitUiIfNeeded(outcome, processed);
        }

        synchronized void record(ToolOutcome outcome) {
            ProcessedToolOutcome processed = processedOutcome(outcome);
            emitUiIfNeeded(outcome, processed);
            int order = orderKey(outcome);
            if (processed != null && recorded.add(order)) {
                recordToolOutcomeForModel(session, context, turnContext, processed);
            }
        }

        private ProcessedToolOutcome processedOutcome(ToolOutcome outcome) {
            int order = orderKey(outcome);
            if (processedByOrder.containsKey(order)) {
                return processedByOrder.get(order);
            }
            ProcessedToolOutcome processed = processToolOutcome(session, outcome);
            processedByOrder.put(order, processed);
            return processed;
        }

        private void emitUiIfNeeded(ToolOutcome outcome, ProcessedToolOutcome processed) {
            int order = orderKey(outcome);
            if (processed != null && uiEmitted.add(order)) {
                emitToolOutcomeUi(session, turnContext, processed);
            }
        }

        private int orderKey(ToolOutcome outcome) {
            int order = outcome == null ? -1 : outcome.order();
            return order >= 0 ? order : System.identityHashCode(outcome);
        }
    }

    private record ProcessedToolOutcome(
            ToolOutcome outcome,
            Tool tool,
            ProcessedToolResult processedResult
    ) {
    }

    private long runAutoCompactIfNeeded(
            Session session,
            TaskContext context,
            CompactTask compactTask,
            String phase,
            long blockedAtOrBelow
    ) {
        if (context.isCancelled() || !session.shouldAutoCompact()) {
            return blockedAtOrBelow;
        }

        long beforeTokens = session.currentContextTokenUsage();
        if (blockedAtOrBelow >= 0 && beforeTokens <= blockedAtOrBelow) {
            return blockedAtOrBelow;
        }

        boolean compacted = compactTask.runInlineAutoCompact(context, phase);
        if (!compacted || context.isCancelled()) {
            return blockedAtOrBelow;
        }
        long afterTokens = session.currentContextTokenUsage();
        if (beforeTokens - afterTokens < MIN_EFFECTIVE_COMPACT_TOKEN_DROP) {
            return afterTokens;
        }
        return -1;
    }

    private void recordProcessedInput(
            Session session,
            TaskContext context,
            TurnContext turnContext
    ) {
        ProcessedTurnInput processedInput = context.processedInput();
        if (processedInput == null) {
            return;
        }

        UserMessage userMessage = processedInput.userMessage();
        TranscriptRecord userRecord = session.recordConversationMessage(userMessage, turnContext);
        session.config().traceRecorder().recordConversationItem(
                context.traceContext(),
                "user",
                userMessage,
                userRecord
        );
        session.events().emit(UiEvents.userMessage(userMessage, turnContext));
        for (ContextMessage contextMessage : processedInput.contextMessages()) {
            TranscriptRecord contextRecord = session.recordConversationMessage(contextMessage, turnContext);
            session.config().traceRecorder().recordConversationItem(
                    context.traceContext(),
                    "context",
                    contextMessage,
                    contextRecord
            );
            session.events().emit(UiEvents.contextMessage(contextMessage, turnContext));
        }
    }

}
