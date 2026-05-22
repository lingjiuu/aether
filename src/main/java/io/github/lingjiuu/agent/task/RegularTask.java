package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.event.UiEvents;
import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.agent.turn.TurnState;
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
        TurnState state = new TurnState();

        CompactTask compactTask = new CompactTask();
        long blockedAutoCompactAtOrBelow = -1;
        int contextWindowRecoveries = 0;
        AutoCompactState autoCompactState = runAutoCompactIfNeeded(
                session,
                context,
                compactTask,
                "pre-turn",
                blockedAutoCompactAtOrBelow
        );
        blockedAutoCompactAtOrBelow = autoCompactState.blockedAtOrBelow();
        if (context.isCancelled()) {
            return;
        }

        List<Skill> turnSkills = session.availableSkills();
        session.recordInitialContextIfChanged(turnContext);
        recordMaterializedInput(session, context.materializedInput(), turnContext);
        recordSkillInjections(session, context.materializedInput(), turnContext, turnSkills);

        while (!context.isCancelled()) {
            state.nextSampling();
            autoCompactState = runAutoCompactIfNeeded(
                    session,
                    context,
                    compactTask,
                    "pre-sampling",
                    blockedAutoCompactAtOrBelow
            );
            blockedAutoCompactAtOrBelow = autoCompactState.blockedAtOrBelow();
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

                state.addToolCalls(toolScope.size());
                List<ToolOutcome> outcomes = context.isCancelled() || Thread.currentThread().isInterrupted()
                        ? toolScope.abortAndDrain()
                        : toolScope.drain();
                recordToolOutcomes(session, turnContext, outcomes);
                if (context.isCancelled() || Thread.currentThread().isInterrupted()) {
                    return;
                }
            }
            autoCompactState = runAutoCompactIfNeeded(
                    session,
                    context,
                    compactTask,
                    "mid-turn",
                    blockedAutoCompactAtOrBelow
            );
            blockedAutoCompactAtOrBelow = autoCompactState.blockedAtOrBelow();
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
                    null
            ));
            case THINKING_START -> session.events().emit(UiEvents.itemStarted(
                    turnContext,
                    UiItemKind.REASONING,
                    event.getItemId(),
                    event.getContentIndex(),
                    null
            ));
            case TOOLCALL_START -> session.events().emit(UiEvents.itemStarted(
                    turnContext,
                    UiItemKind.TOOL_CALL,
                    event.getItemId(),
                    event.getContentIndex(),
                    event.getToolCall()
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
                    event.getDelta()
            ));
            case TEXT_END -> {
                AssistantMessage assistantItem = session.contextBuilder().assistantTextItem(
                        event.getPartial(),
                        event.getContent(),
                        event.getProviderState()
                );
                session.recordAssistant(
                        assistantItem,
                        turnContext
                );
                session.events().emit(UiEvents.itemCompleted(
                        turnContext,
                        UiItemKind.ASSISTANT_TEXT,
                        event.getItemId(),
                        event.getContentIndex(),
                        null,
                        event.getContent()
                ));
            }
            case THINKING_END -> {
                AssistantMessage assistantItem = session.contextBuilder().assistantThinkingItem(
                        event.getPartial(),
                        event.getContent(),
                        event.getProviderState()
                );
                session.recordAssistant(
                        assistantItem,
                        turnContext
                );
                session.events().emit(UiEvents.itemCompleted(
                        turnContext,
                        UiItemKind.REASONING,
                        event.getItemId(),
                        event.getContentIndex(),
                        null,
                        event.getContent()
                ));
            }
            case TOOLCALL_END -> {
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
                        event.getToolCall()
                ));
                session.events().emit(UiEvents.itemCompleted(
                        turnContext,
                        UiItemKind.TOOL_CALL,
                        event.getItemId(),
                        event.getContentIndex(),
                        event.getToolCall(),
                        event.getToolCall() == null ? null : event.getToolCall().getArgumentsJson()
                ));
                toolScope.fork(assistantItem, new ToolCallRef(
                        event.getItemId(),
                        event.getContentIndex(),
                        event.getToolCall()
                ));
            }
            case ERROR -> session.events().emit(UiEvents.error(
                    turnContext,
                    streamErrorMessage(event)
            ));
            default -> {
            }
        }
    }

    private String streamErrorMessage(AssistantStreamEvent event) {
        if (event == null) {
            return "Model stream failed.";
        }
        if (event.getError() != null
                && event.getError().getErrorMessage() != null
                && !event.getError().getErrorMessage().isBlank()) {
            return event.getError().getErrorMessage();
        }
        if (event.getReason() != null && !event.getReason().isBlank()) {
            return event.getReason();
        }
        return "Model stream failed.";
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

    private AutoCompactState runAutoCompactIfNeeded(
            Session session,
            TaskContext context,
            CompactTask compactTask,
            String phase,
            long blockedAtOrBelow
    ) {
        if (context.isCancelled() || !session.shouldAutoCompact()) {
            return new AutoCompactState(blockedAtOrBelow);
        }

        long beforeTokens = session.currentContextTokenUsage();
        if (blockedAtOrBelow >= 0 && beforeTokens <= blockedAtOrBelow) {
            return new AutoCompactState(blockedAtOrBelow);
        }

        boolean compacted = compactTask.runInlineAutoCompact(context, phase);
        if (!compacted || context.isCancelled()) {
            return new AutoCompactState(blockedAtOrBelow);
        }
        long afterTokens = session.currentContextTokenUsage();
        if (beforeTokens - afterTokens < MIN_EFFECTIVE_COMPACT_TOKEN_DROP) {
            return new AutoCompactState(afterTokens);
        }
        return new AutoCompactState(-1);
    }

    private record AutoCompactState(long blockedAtOrBelow) {
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
