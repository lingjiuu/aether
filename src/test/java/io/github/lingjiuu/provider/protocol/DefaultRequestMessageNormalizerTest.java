package io.github.lingjiuu.provider.protocol;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.AttachmentMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.SystemMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.attachment.TextAttachment;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import junit.framework.TestCase;

import java.util.List;

public class DefaultRequestMessageNormalizerTest extends TestCase {

    public void testNormalizesRuntimeMessagesIntoRequestKinds() {
        DefaultRequestMessageNormalizer normalizer = new DefaultRequestMessageNormalizer();

        List<NormalizedRequestMessage> messages = normalizer.normalize(List.of(
                UserMessage.builder()
                        .contents(List.of(TextContent.builder().text("hello").build()))
                        .build(),
                AssistantMessage.builder()
                        .contents(List.of(
                                TextContent.builder().text("answer").build(),
                                ThinkingContent.builder().thinking("reasoning").build(),
                                ToolCallContent.builder()
                                        .toolCallId("call-1")
                                        .toolName("sample_tool")
                                        .argumentsJson("{\"value\":\"UTC\"}")
                                        .build()
                        ))
                        .build(),
                ToolResultMessage.builder()
                        .toolCallId("call-1")
                        .toolName("sample_tool")
                        .contents(List.of(TextContent.builder().text("{\"value\":\"12:00\"}").build()))
                        .build()
        ), List.of());

        assertEquals(3, messages.size());
        assertEquals(NormalizedRequestMessage.Kind.USER, messages.get(0).kind());
        assertEquals(NormalizedRequestMessage.Kind.ASSISTANT, messages.get(1).kind());
        assertEquals(NormalizedRequestMessage.Kind.CONTEXT, messages.get(2).kind());

        NormalizedUserMessage userMessage = (NormalizedUserMessage) messages.get(0);
        assertEquals("hello", ((NormalizedTextContent) userMessage.contents().getFirst()).text());

        NormalizedAssistantMessage assistantMessage = (NormalizedAssistantMessage) messages.get(1);
        assertEquals(3, assistantMessage.contents().size());
        assertEquals("answer", ((NormalizedTextContent) assistantMessage.contents().get(0)).text());
        assertEquals("reasoning", ((NormalizedThinkingContent) assistantMessage.contents().get(1)).thinking());
        NormalizedToolCallContent toolCall = (NormalizedToolCallContent) assistantMessage.contents().get(2);
        assertEquals("call-1", toolCall.toolCallId());
        assertEquals("sample_tool", toolCall.toolName());
        assertEquals("{\"value\":\"UTC\"}", toolCall.argumentsJson());

        NormalizedContextMessage contextMessage = (NormalizedContextMessage) messages.get(2);
        NormalizedToolResultContent toolResult = (NormalizedToolResultContent) contextMessage.contents().getFirst();
        assertEquals("call-1", toolResult.toolCallId());
        assertEquals("sample_tool", toolResult.toolName());
        assertEquals("{\"value\":\"12:00\"}", toolResult.outputText());
    }

    public void testMergesConsecutiveUserAndContextMessages() {
        DefaultRequestMessageNormalizer normalizer = new DefaultRequestMessageNormalizer();

        List<Message> runtimeMessages = List.of(
                UserMessage.builder()
                        .contents(List.of(TextContent.builder().text("first").build()))
                        .build(),
                UserMessage.builder()
                        .contents(List.of(TextContent.builder().text("second").build()))
                        .build(),
                ToolResultMessage.builder()
                        .toolCallId("call-1")
                        .toolName("first_tool")
                        .contents(List.of(TextContent.builder().text("one").build()))
                        .build(),
                ToolResultMessage.builder()
                        .toolCallId("call-2")
                        .toolName("second_tool")
                        .contents(List.of(TextContent.builder().text("two").build()))
                        .build()
        );

        List<NormalizedRequestMessage> messages = normalizer.normalize(runtimeMessages, List.of());

        assertEquals(2, messages.size());
        assertEquals(NormalizedRequestMessage.Kind.USER, messages.get(0).kind());
        assertEquals(NormalizedRequestMessage.Kind.CONTEXT, messages.get(1).kind());
        assertEquals(2, messages.get(0).contents().size());
        assertEquals(2, messages.get(1).contents().size());
        assertEquals("first", ((NormalizedTextContent) messages.get(0).contents().get(0)).text());
        assertEquals("second", ((NormalizedTextContent) messages.get(0).contents().get(1)).text());
        assertEquals("call-1", ((NormalizedToolResultContent) messages.get(1).contents().get(0)).toolCallId());
        assertEquals("call-2", ((NormalizedToolResultContent) messages.get(1).contents().get(1)).toolCallId());
    }

    public void testAttachmentNormalizesToContextAndSystemMarkerIsFiltered() {
        DefaultRequestMessageNormalizer normalizer = new DefaultRequestMessageNormalizer();

        List<NormalizedRequestMessage> messages = normalizer.normalize(List.of(
                AttachmentMessage.builder()
                        .attachment(TextAttachment.builder()
                                .name("project_rules")
                                .content("Use surgical changes.")
                                .build())
                        .build(),
                SystemMessage.builder()
                        .subtype(SystemMessage.Subtype.SNIP_BOUNDARY)
                        .contents(List.of(TextContent.builder().text("marker").build()))
                        .removedMessageIds(List.of("old-message"))
                        .build()
        ), List.of());

        assertEquals(1, messages.size());
        assertEquals(NormalizedRequestMessage.Kind.CONTEXT, messages.getFirst().kind());
        assertEquals("project_rules:\nUse surgical changes.",
                ((NormalizedTextContent) messages.getFirst().contents().getFirst()).text());
    }
}
