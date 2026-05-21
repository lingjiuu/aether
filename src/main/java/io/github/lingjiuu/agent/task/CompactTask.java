package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.compact.CompactPromptBuilder;
import io.github.lingjiuu.event.UiEvent;
import io.github.lingjiuu.event.UiEventType;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.prompt.Prompt;
import io.github.lingjiuu.session.Session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CompactTask implements SessionTask {

    private static final int DEFAULT_MAX_PRESERVED_USER_MESSAGE_CHARS = 80_000;
    private static final int MAX_COMPACT_CONTEXT_RETRIES = 3;
    private static final String USER_MESSAGE_TRUNCATION_MARKER = "\n\n[user message truncated by compact policy]";

    private final CompactPromptBuilder promptBuilder;
    private final int maxPreservedUserMessageChars;

    public CompactTask() {
        this(new CompactPromptBuilder(), DEFAULT_MAX_PRESERVED_USER_MESSAGE_CHARS);
    }

    CompactTask(CompactPromptBuilder promptBuilder, int maxPreservedUserMessageChars) {
        this.promptBuilder = promptBuilder == null ? new CompactPromptBuilder() : promptBuilder;
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
        return compact(context, label, shouldInjectInitialContext(phase));
    }

    private boolean compact(TaskContext context, String trigger) {
        return compact(context, trigger, false);
    }

    private boolean compact(TaskContext context, String trigger, boolean injectInitialContext) {
        Session session = context.session();
        TurnContext turnContext = context.turnContext();
        List<Message> originalMessages = session.contextManager().snapshot();
        session.emit(UiEvent.builder()
                .type(UiEventType.COMPACT_STARTED)
                .sessionId(turnContext.sessionId())
                .turn(context.turn())
                .text(trigger)
                .originalMessageCount(originalMessages.size())
                .build());

        if (originalMessages.isEmpty()) {
            session.emit(UiEvent.builder()
                    .type(UiEventType.COMPACT_SKIPPED)
                    .sessionId(turnContext.sessionId())
                    .turn(context.turn())
                    .text("No context to compact.")
                    .originalMessageCount(originalMessages.size())
                    .replacementMessageCount(originalMessages.size())
                    .build());
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
            session.emit(UiEvent.builder()
                    .type(UiEventType.COMPACT_SKIPPED)
                    .sessionId(turnContext.sessionId())
                    .turn(context.turn())
                    .text(assistantMessage.getErrorMessage())
                    .originalMessageCount(originalMessages.size())
                    .replacementMessageCount(originalMessages.size())
                    .build());
            return false;
        }

        String summary = MessageContents.text(assistantMessage);
        if (summary.isBlank()) {
            summary = "(compact summary was empty)";
        }

        List<UserMessage> preservedUserMessages = preservedUserMessages(originalMessages);
        List<Message> replacementMessages = replacementMessages(
                session,
                turnContext,
                summary,
                preservedUserMessages,
                injectInitialContext
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
        if (injectInitialContext) {
            session.markInitialContextBaseline(turnContext);
        } else {
            session.clearInitialContextBaseline();
        }
        session.recomputeTokenUsageFromHistory(turnContext);
        session.emit(UiEvent.builder()
                .type(UiEventType.COMPACT_FINISHED)
                .sessionId(turnContext.sessionId())
                .turn(context.turn())
                .text(summary)
                .originalMessageCount(originalMessages.size())
                .replacementMessageCount(replacementMessages.size())
                .build());
        return true;
    }

    private AssistantMessage summarize(
            TaskContext context,
            Session session,
            TurnContext turnContext,
            List<Message> originalMessages
    ) {
        List<Message> compactInput = originalMessages;
        int retries = 0;
        while (true) {
            List<Message> normalizedCompactInput = session.contextManager().normalizeMessagesForModel(
                    compactInput,
                    session.config().model().getInput()
            );
            Prompt prompt = promptBuilder.build(session.config(), normalizedCompactInput);
            AssistantMessage assistantMessage = session.sampleModel(
                    context.modelSession(),
                    prompt,
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

    private List<Message> replacementMessages(
            Session session,
            TurnContext turnContext,
            String summary,
            List<UserMessage> preservedUserMessages,
            boolean injectInitialContext
    ) {
        List<Message> replacement = new ArrayList<>();
        List<ContextMessage> initialContextMessages = injectInitialContext
                ? session.fullInitialContextMessages(turnContext)
                : List.of();
        if (!initialContextMessages.isEmpty()
                && preservedUserMessages != null
                && !preservedUserMessages.isEmpty()) {
            replacement.addAll(preservedUserMessages.subList(0, preservedUserMessages.size() - 1));
            replacement.addAll(initialContextMessages);
            replacement.add(preservedUserMessages.getLast());
        } else {
            replacement.addAll(initialContextMessages);
            if (preservedUserMessages != null) {
                replacement.addAll(preservedUserMessages);
            }
        }
        replacement.add(session.contextBuilder().compactSummaryMessage(summary));
        return List.copyOf(replacement);
    }

    private boolean shouldInjectInitialContext(String phase) {
        return "mid-turn".equals(phase) || "context-window-exceeded".equals(phase);
    }

    private List<UserMessage> preservedUserMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty() || maxPreservedUserMessageChars <= 0) {
            return List.of();
        }

        int remainingChars = maxPreservedUserMessageChars;
        List<UserMessage> selected = new ArrayList<>();
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (!(message instanceof UserMessage userMessage) || isCompactSummary(userMessage)) {
                continue;
            }

            String text = MessageContents.text(userMessage);
            if (text.isBlank()) {
                continue;
            }

            if (text.length() <= remainingChars) {
                selected.add(copyUserMessage(text));
                remainingChars -= text.length();
                if (remainingChars == 0) {
                    break;
                }
                continue;
            }

            selected.add(copyUserMessage(text.substring(0, remainingChars) + USER_MESSAGE_TRUNCATION_MARKER));
            break;
        }

        Collections.reverse(selected);
        return List.copyOf(selected);
    }

    private boolean isCompactSummary(UserMessage message) {
        return MessageContents.text(message).startsWith(CompactPromptBuilder.SUMMARY_PREFIX);
    }

    private UserMessage copyUserMessage(String text) {
        return UserMessage.builder()
                .contents(List.of(TextContent.builder()
                        .text(text)
                        .build()))
                .build();
    }

}
