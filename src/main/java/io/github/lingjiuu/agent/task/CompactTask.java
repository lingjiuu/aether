package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.event.UiEvents;
import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.compact.Compaction;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionConfig;

import java.util.List;

public class CompactTask implements SessionTask {

    private static final int MAX_COMPACT_CONTEXT_RETRIES = 3;

    private final int maxPreservedUserMessageChars;

    public CompactTask() {
        this(Compaction.DEFAULT_MAX_PRESERVED_USER_MESSAGE_CHARS);
    }

    CompactTask(int maxPreservedUserMessageChars) {
        this.maxPreservedUserMessageChars = Math.max(0, maxPreservedUserMessageChars);
    }

    @Override
    public TaskKind kind() {
        return TaskKind.COMPACT;
    }

    @Override
    public void run(TaskContext context) {
        compact(context, "manual");
    }

    public boolean runInlineAutoCompact(TaskContext context) {
        return runInlineAutoCompact(context, "auto");
    }

    public boolean runInlineAutoCompact(TaskContext context, String phase) {
        String label = phase == null || phase.isBlank() ? "auto" : "auto:" + phase;
        return compact(context, label, Compaction.InitialContextInjection.forAutoCompactPhase(phase));
    }

    private boolean compact(TaskContext context, String trigger) {
        return compact(context, trigger, Compaction.InitialContextInjection.DO_NOT_INJECT);
    }

    private boolean compact(
            TaskContext context,
            String trigger,
            Compaction.InitialContextInjection initialContextInjection
    ) {
        Session session = context.session();
        TurnContext turnContext = context.turnContext();
        List<Message> originalMessages = session.contextManager().snapshot();
        session.events().emit(UiEvents.compactStarted(turnContext, trigger, originalMessages.size()));

        if (originalMessages.isEmpty()) {
            session.events().emit(UiEvents.compactSkipped(
                    turnContext,
                    "No context to compact.",
                    originalMessages.size(),
                    originalMessages.size()
            ));
            return false;
        }

        if (context.isCancelled()) {
            return false;
        }
        AssistantMessage assistantMessage = summarize(context, session, turnContext, originalMessages);
        if (assistantMessage.getStopReason() == AssistantMessage.StopReason.ABORTED || context.isCancelled()) {
            return false;
        }
        if (assistantMessage.getStopReason() == AssistantMessage.StopReason.ERROR) {
            session.events().emit(UiEvents.compactSkipped(
                    turnContext,
                    assistantMessage.getErrorMessage(),
                    originalMessages.size(),
                    originalMessages.size()
            ));
            return false;
        }

        String summary = MessageContents.text(assistantMessage);
        if (summary.isBlank()) {
            summary = "(compact summary was empty)";
        }

        List<UserMessage> preservedUserMessages = Compaction.preservedUserMessages(
                originalMessages,
                maxPreservedUserMessageChars,
                session.contextBuilder()
        );
        List<ContextMessage> initialContextMessages = initialContextInjection.shouldInject()
                ? session.fullInitialContextMessages(turnContext)
                : List.of();
        List<Message> replacementMessages = Compaction.replacementMessages(
                preservedUserMessages,
                initialContextMessages,
                summary,
                session.contextBuilder()
        );
        if (context.isCancelled()) {
            return false;
        }
        session.recorder().recordCompaction(
                summary,
                replacementMessages,
                turnContext.turn(),
                originalMessages.size(),
                preservedUserMessages.size()
        );
        if (initialContextInjection.shouldInject()) {
            session.markInitialContextBaseline(turnContext);
        } else {
            session.clearInitialContextBaseline();
        }
        session.recomputeTokenUsageFromHistory(turnContext);
        session.events().emit(UiEvents.compactFinished(turnContext, summary, originalMessages.size(), replacementMessages.size()));
        return true;
    }

    private AssistantMessage summarize(
            TaskContext context,
            Session session,
            TurnContext turnContext,
            List<Message> originalMessages
    ) {
        SessionConfig turnConfig = context.sessionConfig();
        List<Message> compactInput = originalMessages;
        int retries = 0;
        while (true) {
            List<Message> normalizedCompactInput = session.contextManager().normalizeMessagesForModel(
                    compactInput,
                    turnConfig.model().getInput()
            );
            LlmRequest request = Compaction.request(
                    turnConfig,
                    normalizedCompactInput,
                    session.contextBuilder()
            );
            AssistantMessage assistantMessage = session.sampleModel(
                    context.modelSession(),
                    request,
                    turnContext,
                    context.cancellationToken()
            );
            if (!session.isContextWindowExceeded(assistantMessage)
                    || retries >= MAX_COMPACT_CONTEXT_RETRIES
                    || compactInput.size() <= 1) {
                return assistantMessage;
            }

            session.markContextWindowFull(turnContext);
            compactInput = List.copyOf(compactInput.subList(1, compactInput.size()));
            retries++;
        }
    }
}
