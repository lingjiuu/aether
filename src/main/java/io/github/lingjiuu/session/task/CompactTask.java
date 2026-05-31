package io.github.lingjiuu.session.task;

import io.github.lingjiuu.event.UiEvents;
import io.github.lingjiuu.session.turn.TurnContext;
import io.github.lingjiuu.compact.Compaction;
import io.github.lingjiuu.model.client.ModelErrorInfo;
import io.github.lingjiuu.model.client.ModelRequest;
import io.github.lingjiuu.model.client.ModelRetryOptions;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.trace.TracePayloads;
import io.github.lingjiuu.trace.TraceSpan;

import java.util.List;

public class CompactTask implements SessionTask {

    private static final int MAX_COMPACT_CONTEXT_RETRIES = 3;

    @Override
    public TaskKind kind() {
        return TaskKind.COMPACT;
    }

    @Override
    public void run(TaskContext context) {
        compact(context, "manual", Compaction.InitialContextInjection.DO_NOT_INJECT);
    }

    public boolean runInlineAutoCompact(TaskContext context, String phase) {
        String label = phase == null || phase.isBlank() ? "auto" : "auto:" + phase;
        return compact(context, label, Compaction.InitialContextInjection.forAutoCompactPhase(phase));
    }

    private boolean compact(
            TaskContext context,
            String trigger,
            Compaction.InitialContextInjection initialContextInjection
    ) {
        Session session = context.session();
        TurnContext turnContext = context.turnContext();
        List<Message> originalMessages = session.contextManager().snapshot();
        TraceSpan span = session.config().traceRecorder().startCompactSpan(
                context.traceContext(),
                trigger,
                originalMessages.size()
        );
        session.events().emit(UiEvents.compactStarted(turnContext, trigger, originalMessages.size()));

        if (originalMessages.isEmpty()) {
            session.events().emit(UiEvents.compactSkipped(
                    turnContext,
                    "No context to compact.",
                    originalMessages.size(),
                    originalMessages.size()
            ));
            span.finish("SKIPPED", TracePayloads.compactOutput(
                    "SKIPPED",
                    originalMessages.size(),
                    originalMessages.size(),
                    "No context to compact."
            ));
            return false;
        }

        if (context.isCancelled()) {
            span.finish("ABORTED", TracePayloads.compactOutput("ABORTED", originalMessages.size(), null, null));
            return false;
        }
        AssistantMessage assistantMessage = summarize(context, session, turnContext, originalMessages);
        if (assistantMessage.isAborted() || context.isCancelled()) {
            span.finish("ABORTED", TracePayloads.compactOutput("ABORTED", originalMessages.size(), null, null));
            return false;
        }
        if (assistantMessage.isError()) {
            session.events().emit(UiEvents.compactSkipped(
                    turnContext,
                    assistantMessage.getErrorMessage(),
                    originalMessages.size(),
                    originalMessages.size()
            ));
            span.finish("FAILED", TracePayloads.compactOutput(
                    "FAILED",
                    originalMessages.size(),
                    originalMessages.size(),
                    assistantMessage.getErrorMessage()
            ));
            return false;
        }

        String summary = MessageContents.text(assistantMessage);
        if (summary.isBlank()) {
            summary = "(compact summary was empty)";
        }

        List<UserMessage> preservedUserMessages = Compaction.preservedUserMessages(
                originalMessages,
                Compaction.DEFAULT_MAX_PRESERVED_USER_MESSAGE_TOKENS,
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
            span.finish("ABORTED", TracePayloads.compactOutput("ABORTED", originalMessages.size(), replacementMessages.size(), null));
            return false;
        }
        session.replaceCompactedHistory(
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
        span.succeed(TracePayloads.compactOutput("COMPLETED", originalMessages.size(), replacementMessages.size(), null));
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
        ModelRetryOptions retryOptions = turnConfig.endpoint() == null
                ? ModelRetryOptions.defaults()
                : turnConfig.endpoint().retryOptions();
        int contextRetries = 0;
        int streamRetries = 0;
        while (true) {
            List<Message> normalizedCompactInput = session.contextManager().normalizeMessagesForModel(
                    compactInput,
                    turnConfig.model().getInput()
            );
            ModelRequest request = Compaction.request(
                    turnConfig,
                    normalizedCompactInput,
                    session.contextBuilder()
            );
            AssistantMessage assistantMessage = session.sampleModelItems(
                    context.modelSession(),
                    request,
                    turnContext,
                    context.cancellationToken(),
                    context.traceContext(),
                    null
            );
            if (!assistantMessage.isContextWindowExceeded()
                    || contextRetries >= MAX_COMPACT_CONTEXT_RETRIES
                    || compactInput.size() <= 1) {
                if (assistantMessage.isRetryableStreamFailure()
                        && streamRetries < retryOptions.streamMaxRetries()) {
                    streamRetries++;
                    session.events().emit(UiEvents.streamRetry(
                            turnContext,
                            streamRetries,
                            retryOptions.streamMaxRetries()
                    ));
                    if (!sleepForRetry(context, retryDelayMillis(retryOptions, streamRetries, assistantMessage.getErrorInfo()))) {
                        return AssistantMessage.aborted();
                    }
                    continue;
                }
                return assistantMessage;
            }

            session.markContextWindowFull(turnContext);
            compactInput = List.copyOf(compactInput.subList(1, compactInput.size()));
            contextRetries++;
            streamRetries = 0;
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
}
