package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.compact.CompactPromptBuilder;
import io.github.lingjiuu.event.UiEvent;
import io.github.lingjiuu.event.UiEventType;
import io.github.lingjiuu.message.AssistantMessage;
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
        Session session = context.session();
        TurnContext turnContext = context.turnContext();
        List<Message> originalMessages = session.contextManager().snapshot();
        session.emit(UiEvent.builder()
                .type(UiEventType.COMPACT_STARTED)
                .sessionId(turnContext.sessionId())
                .turn(context.turn())
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
            return;
        }

        if (context.isCancelled()) {
            return;
        }
        Prompt prompt = promptBuilder.build(session.config(), originalMessages);
        AssistantMessage assistantMessage = session.sampleModel(
                context.modelSession(),
                prompt,
                turnContext,
                context.cancellationToken()
        );
        if (assistantMessage.getStopReason() == AssistantMessage.StopReason.ABORTED || context.isCancelled()) {
            return;
        }

        String summary = MessageContents.text(assistantMessage);
        if (summary.isBlank()) {
            summary = "(compact summary was empty)";
        }

        List<UserMessage> preservedUserMessages = preservedUserMessages(originalMessages);
        List<Message> replacementMessages = replacementMessages(session, summary, preservedUserMessages);
        if (context.isCancelled()) {
            return;
        }
        session.recorder().recordCompaction(
                summary,
                replacementMessages,
                turnContext.turn(),
                originalMessages.size(),
                preservedUserMessages.size()
        );
        session.invalidateReferenceEnvironmentContext();
        session.emit(UiEvent.builder()
                .type(UiEventType.COMPACT_FINISHED)
                .sessionId(turnContext.sessionId())
                .turn(context.turn())
                .text(summary)
                .originalMessageCount(originalMessages.size())
                .replacementMessageCount(replacementMessages.size())
                .build());
    }

    private List<Message> replacementMessages(
            Session session,
            String summary,
            List<UserMessage> preservedUserMessages
    ) {
        List<Message> replacement = new ArrayList<>();
        replacement.addAll(preservedUserMessages);
        replacement.add(session.contextBuilder().compactSummaryMessage(summary));
        return List.copyOf(replacement);
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
