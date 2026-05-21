package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.agent.turn.TurnState;
import io.github.lingjiuu.event.UiEvent;
import io.github.lingjiuu.event.UiEventType;
import io.github.lingjiuu.input.MaterializedInput;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.prompt.Prompt;
import io.github.lingjiuu.prompt.PromptBuildInput;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.skill.Skill;
import io.github.lingjiuu.skill.SkillInjection;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRunResult;
import io.github.lingjiuu.tool.permission.ApprovalId;
import io.github.lingjiuu.tool.permission.ApprovalRequest;
import io.github.lingjiuu.tool.permission.ApprovalResponse;
import io.github.lingjiuu.tool.permission.PermissionDecision;
import io.github.lingjiuu.tool.permission.PermissionMode;

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
            AssistantMessage assistantMessage = session.sampleModel(
                    context.modelSession(),
                    prompt,
                    turnContext,
                    context.cancellationToken()
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
            session.recordAssistantAndEmit(assistantMessage, turnContext);

            List<ToolCallContent> toolCalls = MessageContents.toolCalls(assistantMessage);
            if (toolCalls.isEmpty()) {
                session.emitFinalAnswer(assistantMessage, turnContext);
                return;
            }

            state.addToolCalls(toolCalls.size());
            runToolCalls(session, context, turnContext, assistantMessage, toolCalls);
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

    private void runToolCalls(
            Session session,
            TaskContext context,
            TurnContext turnContext,
            AssistantMessage assistantMessage,
            List<ToolCallContent> toolCalls
    ) {
        for (ToolCallContent toolCall : toolCalls) {
            if (context.isCancelled()) {
                return;
            }
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

            ToolRunResult prepared = session.toolRunner().prepare(
                    assistantMessage,
                    toolCall,
                    session.activeToolNames(),
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
            ToolExecutionResult executionResult = runPreparedToolCall(session, turnContext, prepared);
            if (context.isCancelled()) {
                return;
            }
            ToolResultMessage result = session.toolResultMessage(toolCall, executionResult);
            session.recordToolResultAndEmit(toolCall, result, turnContext);
        }
    }

    private ToolExecutionResult runPreparedToolCall(
            Session session,
            TurnContext turnContext,
            ToolRunResult prepared
    ) {
        if (!prepared.ready()) {
            return prepared.failureResult();
        }

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
}
