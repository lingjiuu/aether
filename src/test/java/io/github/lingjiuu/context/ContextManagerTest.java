package io.github.lingjiuu.context;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import junit.framework.TestCase;

import java.util.List;

public class ContextManagerTest extends TestCase {

    public void testNormalizeMessagesForModelAddsMissingToolResultBeforeNextUserMessage() {
        ContextManager contextManager = new ContextManager();
        AssistantMessage assistant = AssistantMessage.builder()
                .contents(List.of(ToolCallContent.builder()
                        .toolCallId("call-1")
                        .toolName("bash")
                        .argumentsJson("{\"cmd\":\"pwd\"}")
                        .build()))
                .build();
        UserMessage user = UserMessage.builder()
                .contents(List.of(TextContent.builder()
                        .text("continue")
                        .build()))
                .build();

        List<Message> normalized = contextManager.normalizeMessagesForModel(
                List.of(assistant, user),
                List.of("text")
        );

        assertEquals(3, normalized.size());
        assertSame(assistant, normalized.get(0));
        assertTrue(normalized.get(1) instanceof ToolResultMessage);
        assertSame(user, normalized.get(2));

        ToolResultMessage synthetic = (ToolResultMessage) normalized.get(1);
        assertEquals("call-1", synthetic.getToolCallId());
        assertEquals("bash", synthetic.getToolName());
        assertEquals("aborted", io.github.lingjiuu.message.MessageContents.text(synthetic));
    }

    public void testNormalizeMessagesForModelRemovesOrphanToolResult() {
        ContextManager contextManager = new ContextManager();
        ToolResultMessage orphan = ToolResultMessage.builder()
                .toolCallId("missing-call")
                .toolName("bash")
                .contents(List.of(TextContent.builder()
                        .text("output")
                        .build()))
                .build();

        List<Message> normalized = contextManager.normalizeMessagesForModel(
                List.of(orphan),
                List.of("text")
        );

        assertTrue(normalized.isEmpty());
    }

    public void testNormalizeMessagesForModelStripsImagesWhenUnsupported() {
        ContextManager contextManager = new ContextManager();
        UserMessage user = UserMessage.builder()
                .contents(List.of(
                        TextContent.builder().text("see image").build(),
                        ImageContent.builder().mimeType("image/png").data("abc123").build()
                ))
                .build();

        List<Message> normalized = contextManager.normalizeMessagesForModel(
                List.of(user),
                List.of("text")
        );

        assertEquals(1, normalized.size());
        assertTrue(normalized.get(0) instanceof UserMessage);
        assertNotSame(user, normalized.get(0));
        assertEquals(2, normalized.get(0).messageContents().size());
        assertTrue(normalized.get(0).messageContents().get(1) instanceof TextContent);
        assertEquals(
                "image content omitted because you do not support image input",
                ((TextContent) normalized.get(0).messageContents().get(1)).getText()
        );
    }

    public void testNormalizeMessagesForModelKeepsImagesWhenSupported() {
        ContextManager contextManager = new ContextManager();
        UserMessage user = UserMessage.builder()
                .contents(List.of(
                        TextContent.builder().text("see image").build(),
                        ImageContent.builder().mimeType("image/png").data("abc123").build()
                ))
                .build();

        List<Message> normalized = contextManager.normalizeMessagesForModel(
                List.of(user),
                List.of("text", "image")
        );

        assertEquals(1, normalized.size());
        assertSame(user, normalized.get(0));
        assertTrue(normalized.get(0).messageContents().get(1) instanceof ImageContent);
    }
}
