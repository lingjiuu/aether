package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.event.UiEvents;
import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.protocol.UiItemKind;
import io.github.lingjiuu.input.MaterializedInput;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.prompt.Prompt;
import io.github.lingjiuu.prompt.PromptBuildInput;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.skill.Skill;
import io.github.lingjiuu.skill.SkillInjection;

import java.util.List;

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

        List<Skill> turnSkills = session.availableSkills();
        session.recordInitialContextIfChanged(turnContext);
        recordMaterializedInput(session, context.materializedInput(), turnContext);
        recordSkillInjections(session, context.materializedInput(), turnContext, turnSkills);

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
                    .normalizeMessagesForModel(session.config().model().getInput());
            Prompt prompt = session.promptBuilder().build(new PromptBuildInput(
                    session.config(),
                    messages,
                    session.activeTools(),
                    turnSkills
            ));
            try (ToolScope toolScope = ToolScope.open(session, context, turnContext)) {
                AssistantMessage assistantMessage = session.sampleModelItems(
                        context.modelSession(),
                        prompt,
                        turnContext,
                        context.cancellationToken(),
                        event -> handleStreamItem(session, turnContext, toolScope, event)
                );
                if (context.isCancelled() || assistantMessage.getStopReason() == AssistantMessage.StopReason.ABORTED) {
                    recordToolOutcomes(session, turnContext, toolScope.abortAndDrain());
                    return;
                }
                if (assistantMessage.getStopReason() == AssistantMessage.StopReason.ERROR) {
                    recordToolOutcomes(session, turnContext, toolScope.abortAndDrain());
                    if (session.isContextWindowExceeded(assistantMessage)
                            && contextWindowRecoveries < MAX_CONTEXT_WINDOW_RECOVERIES) {
                        contextWindowRecoveries++;
                        session.markContextWindowFull(turnContext);
                        if (compactTask.runInlineAutoCompact(context, "context-window-exceeded")) {
                            blockedAutoCompactAtOrBelow = -1;
                            continue;
                        }
                    }
                    session.recordAssistant(assistantMessage, turnContext);
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
                    session.toolRegistry().findDefinition(event.getToolName())
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
                    session.toolRegistry().findDefinition(event.getToolName()),
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
        AssistantMessage assistantItem = session.contextBuilder().assistantTextItem(
                event.getPartial(),
                event.getContent(),
                event.getProviderState()
        );
        session.recordAssistant(assistantItem, turnContext);
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
        AssistantMessage assistantItem = session.contextBuilder().assistantThinkingItem(
                event.getPartial(),
                event.getContent(),
                event.getProviderState()
        );
        session.recordAssistant(assistantItem, turnContext);
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

        AssistantMessage assistantItem = session.contextBuilder().assistantToolCallItem(
                event.getPartial(),
                event.getToolCall(),
                event.getProviderState()
        );
        session.recordAssistant(assistantItem, turnContext);
        session.events().emit(UiEvents.toolArgumentsDone(
                turnContext,
                event.getItemId(),
                event.getContentIndex(),
                event.getToolCall(),
                session.toolRegistry().findDefinition(event.getToolName())
        ));
        session.events().emit(UiEvents.itemCompleted(
                turnContext,
                UiItemKind.TOOL_CALL,
                event.getItemId(),
                event.getContentIndex(),
                event.getToolCall(),
                session.toolRegistry().findDefinition(event.getToolName()),
                event.getToolCall().getArgumentsJson()
        ));
        toolScope.fork(assistantItem, new ToolCallRef(
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
        for (ToolOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            ToolCallRef toolCallRef = outcome.toolCallRef();
            ToolResultMessage result = session.toolResultMessage(toolCallRef.toolCall(), outcome.executionResult());
            session.recordToolResult(
                    toolCallRef.itemId(),
                    toolCallRef.contentIndex(),
                    toolCallRef.toolCall(),
                    result,
                    turnContext,
                    outcome.status(),
                    outcome.durationMs()
            );
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

    private void recordMaterializedInput(
            Session session,
            MaterializedInput materializedInput,
            TurnContext turnContext
    ) {
        if (materializedInput == null) {
            return;
        }

        session.recordUserMessage(materializedInput.userMessage(), turnContext);
        materializedInput.contextMessages()
                .forEach(contextMessage -> session.recordContextMessage(contextMessage, turnContext));
    }

    private void recordSkillInjections(
            Session session,
            MaterializedInput materializedInput,
            TurnContext turnContext,
            List<Skill> turnSkills
    ) {
        if (materializedInput == null) {
            return;
        }
        List<SkillInjection> injections = session.skillsManager()
                .resolveSkillInjections(materializedInput.turnInput(), turnSkills);
        for (SkillInjection injection : injections) {
            session.recordContextMessage(session.contextBuilder().skillContextMessage(injection), turnContext);
        }
    }

}
