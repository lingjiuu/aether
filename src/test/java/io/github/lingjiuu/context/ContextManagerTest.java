package io.github.lingjiuu.context;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.result.ToolResultReplacement;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    public void testNormalizeMessagesForModelAllowsConsecutiveToolCallItemsBeforeResults() {
        ContextManager contextManager = new ContextManager();
        AssistantMessage firstCall = AssistantMessage.builder()
                .contents(List.of(ToolCallContent.builder()
                        .toolCallId("call-1")
                        .toolName("read")
                        .argumentsJson("{\"path\":\"a.txt\"}")
                        .build()))
                .build();
        AssistantMessage secondCall = AssistantMessage.builder()
                .contents(List.of(ToolCallContent.builder()
                        .toolCallId("call-2")
                        .toolName("read")
                        .argumentsJson("{\"path\":\"b.txt\"}")
                        .build()))
                .build();
        ToolResultMessage firstResult = ToolResultMessage.builder()
                .toolCallId("call-1")
                .toolName("read")
                .contents(List.of(TextContent.builder().text("a").build()))
                .build();
        ToolResultMessage secondResult = ToolResultMessage.builder()
                .toolCallId("call-2")
                .toolName("read")
                .contents(List.of(TextContent.builder().text("b").build()))
                .build();

        List<Message> normalized = contextManager.normalizeMessagesForModel(
                List.of(firstCall, secondCall, firstResult, secondResult),
                List.of("text")
        );

        assertEquals(List.of(firstCall, secondCall, firstResult, secondResult), normalized);
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

    public void testRecordDoesNotTrimToolResults() {
        ContextManager contextManager = new ContextManager();
        String large = "tool-output".repeat(3_000);
        ToolResultMessage toolResult = ToolResultMessage.builder()
                .toolCallId("call-1")
                .toolName("bash")
                .contents(List.of(TextContent.builder().text(large).build()))
                .build();

        contextManager.record(toolResult);

        assertSame(toolResult, contextManager.snapshot().getFirst());
        assertEquals(large, io.github.lingjiuu.message.MessageContents.text(contextManager.snapshot().getFirst()));
    }

    public void testApplyToolResultBudgetForModelGroupsByToolBatchIdAndReplacesInMemory() {
        ContextManager contextManager = new ContextManager();
        ToolResultMessage batchOneFirst = toolResult("message-1", "call-1", "batch-1", "raw-1");
        ToolResultMessage batchOneSecond = toolResult("message-2", "call-2", "batch-1", "raw-2");
        ToolResultMessage batchTwo = toolResult("message-3", "call-3", "batch-2", "raw-3");
        contextManager.recordAll(List.of(
                assistantToolCall("call-1", "batch-1"),
                batchOneFirst,
                assistantToolCall("call-2", "batch-1"),
                batchOneSecond,
                assistantToolCall("call-3", "batch-2"),
                batchTwo
        ));

        AtomicInteger budgetCalls = new AtomicInteger();
        List<List<String>> seenBatches = new ArrayList<>();
        List<ToolResultReplacement> replacements = contextManager.applyToolResultBudgetForModel(batch -> {
            budgetCalls.incrementAndGet();
            seenBatches.add(batch.stream().map(ToolResultMessage::getToolCallId).toList());
            if (!"batch-1".equals(batch.getFirst().getToolBatchId())) {
                return List.of();
            }
            ToolResultMessage original = batch.getFirst();
            return List.of(new ToolResultReplacement(
                    original.getId(),
                    original.getToolCallId(),
                    original.getToolBatchId(),
                    toolResult(original.getId(), original.getToolCallId(), original.getToolBatchId(), "preview-1"),
                    List.of()
            ));
        });

        assertEquals(2, budgetCalls.get());
        assertEquals(List.of(List.of("call-1", "call-2"), List.of("call-3")), seenBatches);
        assertEquals(1, replacements.size());
        List<Message> snapshot = contextManager.snapshot();
        assertEquals("preview-1", io.github.lingjiuu.message.MessageContents.text(snapshot.get(1)));
        assertEquals("raw-2", io.github.lingjiuu.message.MessageContents.text(snapshot.get(3)));
        assertEquals("raw-3", io.github.lingjiuu.message.MessageContents.text(snapshot.get(5)));
    }

    private AssistantMessage assistantToolCall(String toolCallId, String toolBatchId) {
        return AssistantMessage.builder()
                .contents(List.of(ToolCallContent.builder()
                        .toolCallId(toolCallId)
                        .toolBatchId(toolBatchId)
                        .toolName("bash")
                        .argumentsJson("{}")
                        .build()))
                .build();
    }

    private ToolResultMessage toolResult(String id, String toolCallId, String toolBatchId, String text) {
        return ToolResultMessage.builder()
                .id(id)
                .toolCallId(toolCallId)
                .toolBatchId(toolBatchId)
                .toolName("bash")
                .contents(List.of(TextContent.builder()
                        .text(text)
                        .build()))
                .build();
    }
}
