package io.github.lingjiuu.session.task;

import io.github.lingjiuu.event.UiEvents;
import io.github.lingjiuu.session.turn.TurnContext;
import io.github.lingjiuu.protocol.UiItemKind;
import io.github.lingjiuu.input.ProcessedTurnInput;
import io.github.lingjiuu.model.client.AssistantStreamEvent;
import io.github.lingjiuu.model.client.ModelRequest;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.result.ToolResultInput;
import io.github.lingjiuu.tool.result.ToolResultProcessor;

import java.util.ArrayList;
import java.util.List;

public class RegularTask implements SessionTask {

    private static final long MIN_EFFECTIVE_COMPACT_TOKEN_DROP = 512;
    private static final int MAX_CONTEXT_WINDOW_RECOVERIES = 2;
    private static final int MAX_STREAM_RECOVERIES = 2;

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
        recordProcessedInput(session, context.processedInput(), turnContext);

        int streamRecoveries = 0;
        while (!context.isCancelled()) {
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
            try (ToolScope toolScope = ToolScope.open(session, context, turnContext)) {
                AssistantMessage assistantMessage = session.sampleModelItems(
                        context.modelSession(),
                        request,
                        turnContext,
                        context.cancellationToken(),
                        event -> handleStreamItem(session, turnContext, toolScope, event)
                );
                if (context.isCancelled() || assistantMessage.isAborted()) {
                    recordToolOutcomes(session, turnContext, toolScope.abortAndDrain());
                    return;
                }
                if (assistantMessage.isError()) {
                    recordToolOutcomes(session, turnContext, toolScope.drain());
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
                            && streamRecoveries < MAX_STREAM_RECOVERIES) {
                        streamRecoveries++;
                        session.events().emit(UiEvents.streamRetry(
                                turnContext,
                                streamRecoveries,
                                MAX_STREAM_RECOVERIES + 1
                        ));
                        continue;
                    }
                    session.recordConversationMessage(assistantMessage, turnContext);
                    session.events().emit(UiEvents.error(turnContext, assistantMessage.getErrorMessage()));
                    return;
                }

                if (toolScope.size() == 0) {
                    return;
                }

                List<ToolOutcome> outcomes = context.isCancelled() || Thread.currentThread().isInterrupted()
                        ? toolScope.abortAndDrain()
                        : toolScope.drain();
                recordToolOutcomes(session, turnContext, outcomes);
                if (context.isCancelled() || Thread.currentThread().isInterrupted()) {
                    return;
                }
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

    private void handleStreamItem(
            Session session,
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
            case TEXT_END -> completeTextItem(session, turnContext, event);
            case THINKING_END -> completeThinkingItem(session, turnContext, event);
            case TOOLCALL_END -> completeToolCallItemAndFork(session, turnContext, toolScope, event);
            case ERROR -> {
            }
            default -> {
            }
        }
    }

    private void completeTextItem(Session session, TurnContext turnContext, AssistantStreamEvent event) {
        AssistantMessage assistantMessage = session.contextBuilder().assistantTextMessage(
                event.getPartial(),
                event.getContent(),
                event.getProviderState()
        );
        session.recordConversationMessage(assistantMessage, turnContext);
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

    private void completeThinkingItem(Session session, TurnContext turnContext, AssistantStreamEvent event) {
        AssistantMessage assistantMessage = session.contextBuilder().assistantThinkingMessage(
                event.getPartial(),
                event.getContent(),
                event.getProviderState()
        );
        session.recordConversationMessage(assistantMessage, turnContext);
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
        session.recordConversationMessage(assistantMessage, turnContext);
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

    private void recordToolOutcomes(
            Session session,
            TurnContext turnContext,
            List<ToolOutcome> outcomes
    ) {
        List<ToolOutcome> validOutcomes = new ArrayList<>();
        List<Tool> tools = new ArrayList<>();
        List<ToolResultInput> inputs = new ArrayList<>();
        for (ToolOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            ToolCallRef toolCallRef = outcome.toolCallRef();
            if (toolCallRef == null || toolCallRef.toolCall() == null) {
                continue;
            }
            Tool tool = session.toolRegistry().findTool(
                    toolCallRef.toolCall().getToolName()
            );
            validOutcomes.add(outcome);
            tools.add(tool);
            inputs.add(new ToolResultInput(toolCallRef.toolCall(), tool, outcome.executionResult()));
        }

        List<ToolResultMessage> toolResults = new ToolResultProcessor(
                session.contextBuilder(),
                session.toolArtifactStore()
        ).processBatch(inputs);

        for (int i = 0; i < validOutcomes.size(); i++) {
            ToolOutcome outcome = validOutcomes.get(i);
            ToolCallRef toolCallRef = outcome.toolCallRef();
            Tool tool = tools.get(i);
            ToolResultMessage toolResult = toolResults.get(i);
            session.recordConversationMessage(toolResult, turnContext);
            session.events().emit(UiEvents.toolExecutionEnd(
                    toolCallRef.itemId(),
                    toolCallRef.contentIndex(),
                    toolCallRef.toolCall(),
                    tool,
                    toolResult,
                    outcome.status(),
                    outcome.durationMs(),
                    turnContext
            ));
            session.events().emit(UiEvents.toolResult(
                    toolCallRef.itemId(),
                    toolCallRef.contentIndex(),
                    toolCallRef.toolCall(),
                    toolResult,
                    outcome.status(),
                    outcome.durationMs(),
                    turnContext
            ));
        }
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
            ProcessedTurnInput processedInput,
            TurnContext turnContext
    ) {
        if (processedInput == null) {
            return;
        }

        UserMessage userMessage = processedInput.userMessage();
        session.recordConversationMessage(userMessage, turnContext);
        session.events().emit(UiEvents.userMessage(userMessage, turnContext));
        for (ContextMessage contextMessage : processedInput.contextMessages()) {
            session.recordConversationMessage(contextMessage, turnContext);
            session.events().emit(UiEvents.contextMessage(contextMessage, turnContext));
        }
    }

}
