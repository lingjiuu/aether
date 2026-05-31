package io.github.lingjiuu.compact;

import io.github.lingjiuu.TestModelSelections;
import io.github.lingjiuu.context.ContextBuilder;
import io.github.lingjiuu.model.client.ModelRequest;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.session.SessionConfig;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;

public class CompactionTest extends TestCase {

    private final ContextBuilder contextBuilder = new ContextBuilder();

    public void testRequestAppendsSummarizationPrompt() {
        ModelRequest request = Compaction.request(sessionConfig(), List.of(userMessage("hello")), contextBuilder);

        assertEquals("base instructions", request.getBaseInstructions());
        assertEquals(0, request.getTools().size());
        assertEquals(2, request.getMessages().size());
        assertEquals("hello", MessageContents.text(request.getMessages().getFirst()));
        assertTrue(MessageContents.text(request.getMessages().getLast()).contains("CONTEXT CHECKPOINT COMPACTION"));
    }

    public void testPreservedUserMessagesSkipsPriorSummaryAndTruncatesLatestUserMessageByTokenBudget() {
        List<UserMessage> preserved = Compaction.preservedUserMessages(List.of(
                userMessage("older request"),
                Compaction.summaryMessage("old summary", contextBuilder),
                userMessage("recent request")
        ), 1, contextBuilder);

        assertEquals(1, preserved.size());
        assertEquals("rece\n\n[user message truncated by compact policy]", MessageContents.text(preserved.getFirst()));
    }

    public void testPreservedUserMessagesKeepsRecentMessagesWithinTokenBudget() {
        List<UserMessage> preserved = Compaction.preservedUserMessages(List.of(
                userMessage("oldest request"),
                userMessage("middle request"),
                userMessage("newest request")
        ), 8, contextBuilder);

        assertEquals(2, preserved.size());
        assertEquals("middle request", MessageContents.text(preserved.get(0)));
        assertEquals("newest request", MessageContents.text(preserved.get(1)));
    }

    public void testReplacementMessagesInsertInitialContextBeforeLastUserMessage() {
        List<Message> replacement = Compaction.replacementMessages(
                List.of(userMessage("first"), userMessage("last")),
                List.of(contextMessage("context")),
                "summary",
                contextBuilder
        );

        assertEquals(4, replacement.size());
        assertEquals("first", MessageContents.text(replacement.get(0)));
        assertEquals("context", MessageContents.text(replacement.get(1)));
        assertEquals("last", MessageContents.text(replacement.get(2)));
        assertTrue(Compaction.isSummaryText(MessageContents.text(replacement.get(3))));
        assertTrue(MessageContents.text(replacement.get(3)).endsWith("\nsummary"));
    }

    private SessionConfig sessionConfig() {
        return new SessionConfig(
                null,
                "base instructions",
                "",
                "",
                List.of(),
                Path.of(".").toAbsolutePath().normalize(),
                TestModelSelections.fakeSelection(),
                null,
                List.of(),
                List.of()
        );
    }

    private UserMessage userMessage(String text) {
        return contextBuilder.userMessage(text);
    }

    private ContextMessage contextMessage(String text) {
        return ContextMessage.builder()
                .kind(ContextMessage.ContextKind.INFORMATIONAL)
                .contents(List.of(TextContent.builder()
                        .text(text)
                        .build()))
                .build();
    }
}
