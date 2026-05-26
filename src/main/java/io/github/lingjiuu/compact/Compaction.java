package io.github.lingjiuu.compact;

import io.github.lingjiuu.context.ContextBuilder;
import io.github.lingjiuu.llm.LlmCallOptions;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.session.SessionConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Compaction {

    public static final int DEFAULT_MAX_PRESERVED_USER_MESSAGE_CHARS = 80_000;

    public static final String SUMMARY_PREFIX = "Another language model started to solve this problem and produced a summary of its thinking process. You also have access to the state of the tools that were used by that language model. Use this to build on the work that has already been done and avoid duplicating work. Here is the summary produced by the other language model, use the information in this summary to assist with your own analysis:";

    private static final String SUMMARIZATION_PROMPT = """
            You are performing a CONTEXT CHECKPOINT COMPACTION. Create a handoff summary for another LLM that will resume the task.

            Include:
            - Current progress and key decisions made
            - Important context, constraints, or user preferences
            - What remains to be done (clear next steps)
            - Any critical data, examples, or references needed to continue

            Be concise, structured, and focused on helping the next LLM seamlessly continue the work.
            """;

    private static final String EMPTY_SUMMARY = "(compact summary was empty)";
    private static final String USER_MESSAGE_TRUNCATION_MARKER = "\n\n[user message truncated by compact policy]";

    private Compaction() {
    }

    public static LlmRequest request(
            SessionConfig config,
            List<Message> messages,
            ContextBuilder contextBuilder
    ) {
        if (config == null || config.model() == null) {
            throw new IllegalStateException("Compact requires a configured model.");
        }
        ContextBuilder builder = contextBuilder == null ? new ContextBuilder() : contextBuilder;
        List<Message> compactMessages = new ArrayList<>();
        if (messages != null) {
            compactMessages.addAll(messages);
        }
        compactMessages.add(builder.userMessage(SUMMARIZATION_PROMPT.trim()));
        return LlmRequest.builder()
                .baseInstructions(config.baseInstructions())
                .model(config.model())
                .tools(List.of())
                .messages(compactMessages)
                .callOptions(LlmCallOptions.builder()
                        .reasoning(config.reasoning())
                        .build())
                .build();
    }

    public static UserMessage summaryMessage(String summary, ContextBuilder contextBuilder) {
        ContextBuilder builder = contextBuilder == null ? new ContextBuilder() : contextBuilder;
        String safeSummary = summary == null || summary.isBlank()
                ? EMPTY_SUMMARY
                : summary.trim();
        return builder.userMessage(SUMMARY_PREFIX + "\n" + safeSummary);
    }

    public static boolean isSummaryMessage(UserMessage message) {
        return isSummaryText(MessageContents.text(message));
    }

    public static boolean isSummaryText(String text) {
        return text != null && text.startsWith(SUMMARY_PREFIX + "\n");
    }

    public static List<Message> replacementMessages(
            List<UserMessage> preservedUserMessages,
            List<ContextMessage> initialContextMessages,
            String summary,
            ContextBuilder contextBuilder
    ) {
        List<ContextMessage> safeInitialContext = initialContextMessages == null
                ? List.of()
                : List.copyOf(initialContextMessages);
        List<UserMessage> safePreservedUsers = preservedUserMessages == null
                ? List.of()
                : List.copyOf(preservedUserMessages);
        List<Message> replacement = new ArrayList<>();
        if (!safeInitialContext.isEmpty() && !safePreservedUsers.isEmpty()) {
            replacement.addAll(safePreservedUsers.subList(0, safePreservedUsers.size() - 1));
            replacement.addAll(safeInitialContext);
            replacement.add(safePreservedUsers.getLast());
        } else {
            replacement.addAll(safeInitialContext);
            replacement.addAll(safePreservedUsers);
        }
        replacement.add(summaryMessage(summary, contextBuilder));
        return List.copyOf(replacement);
    }

    public static List<UserMessage> preservedUserMessages(
            List<Message> messages,
            int maxChars,
            ContextBuilder contextBuilder
    ) {
        if (messages == null || messages.isEmpty() || maxChars <= 0) {
            return List.of();
        }

        ContextBuilder builder = contextBuilder == null ? new ContextBuilder() : contextBuilder;
        int remainingChars = maxChars;
        List<UserMessage> selected = new ArrayList<>();
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (!(message instanceof UserMessage userMessage) || isSummaryMessage(userMessage)) {
                continue;
            }

            String text = MessageContents.text(userMessage);
            if (text.isBlank()) {
                continue;
            }

            if (text.length() <= remainingChars) {
                selected.add(builder.userMessage(text));
                remainingChars -= text.length();
                if (remainingChars == 0) {
                    break;
                }
                continue;
            }

            selected.add(builder.userMessage(text.substring(0, remainingChars) + USER_MESSAGE_TRUNCATION_MARKER));
            break;
        }

        Collections.reverse(selected);
        return List.copyOf(selected);
    }

    public enum InitialContextInjection {
        BEFORE_LAST_USER_MESSAGE,
        DO_NOT_INJECT;

        public static InitialContextInjection forAutoCompactPhase(String phase) {
            return "mid-turn".equals(phase) || "context-window-exceeded".equals(phase)
                    ? BEFORE_LAST_USER_MESSAGE
                    : DO_NOT_INJECT;
        }

        public boolean shouldInject() {
            return this == BEFORE_LAST_USER_MESSAGE;
        }
    }
}
