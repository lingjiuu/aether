package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.agent.turn.TurnState;
import io.github.lingjiuu.event.UiEvent;
import io.github.lingjiuu.event.UiEventType;
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
        session.emit(UiEvent.builder()
                .type(UiEventType.TURN_STARTED)
                .sessionId(turnContext.sessionId())
                .turn(turnContext.turn())
                .build());

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
                    return;
                }
                if (assistantMessage.getStopReason() == AssistantMessage.StopReason.ERROR) {
                    if (session.isContextWindowExceeded(assistantMessage)
                            && contextWindowRecoveries < MAX_CONTEXT_WINDOW_RECOVERIES) {
                        contextWindowRecoveries++;
                        session.markContextWindowFull(turnContext);
                        if (compactTask.runInlineAutoCompact(context, "context-window-exceeded")) {
                            blockedAutoCompactAtOrBelow = -1;
                            continue;
                        }
                    }
                    session.recordAssistantAndEmit(assistantMessage, turnContext);
                    session.emitError(turnContext, assistantMessage.getErrorMessage());
                    return;
                }

                if (toolScope.size() == 0) {
                    session.emitFinalAnswer(assistantMessage, turnContext);
                    return;
                }

                state.addToolCalls(toolScope.size());
                recordToolOutcomes(session, turnContext, toolScope.drain());
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
            case TEXT_END -> session.recordAssistantAndEmit(
                    session.contextBuilder().assistantTextItem(
                            event.getPartial(),
                            event.getContent(),
                            event.getProviderState()
                    ),
                    turnContext
            );
            case THINKING_END -> session.recordAssistantAndEmit(
                    session.contextBuilder().assistantThinkingItem(
                            event.getPartial(),
                            event.getContent(),
                            event.getProviderState()
                    ),
                    turnContext
            );
            case TOOLCALL_END -> {
                AssistantMessage assistantItem = session.contextBuilder().assistantToolCallItem(
                        event.getPartial(),
                        event.getToolCall(),
                        event.getProviderState()
                );
                session.recordAssistantAndEmit(assistantItem, turnContext);
                toolScope.fork(assistantItem, event.getToolCall());
            }
            default -> {
            }
        }
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
            ToolResultMessage result = session.toolResultMessage(outcome.toolCall(), outcome.executionResult());
            session.recordToolResultAndEmit(outcome.toolCall(), result, turnContext);
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

        session.recordUserMessageAndEmit(materializedInput.userMessage(), turnContext);
        materializedInput.contextMessages()
                .forEach(contextMessage -> session.recordContextMessageAndEmit(contextMessage, turnContext));
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
            session.recordContextMessageAndEmit(session.contextBuilder().skillContextMessage(injection), turnContext);
        }
    }

}
