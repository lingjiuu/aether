package io.github.lingjiuu.session.task;

import io.github.lingjiuu.TestModelSelections;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.model.client.AssistantStream;
import io.github.lingjiuu.model.client.AssistantStreamEvent;
import io.github.lingjiuu.model.client.ModelClient;
import io.github.lingjiuu.model.client.ModelRequest;
import io.github.lingjiuu.protocol.UiEventType;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.permission.PermissionPreset;
import io.github.lingjiuu.tool.result.ToolResultPolicy;
import io.github.lingjiuu.transcript.TranscriptRecord;
import io.github.lingjiuu.transcript.TranscriptStore;
import io.github.lingjiuu.transcript.item.MessageTranscriptItem;
import io.github.lingjiuu.wire.WireAdapter;
import io.github.lingjiuu.wire.WireAdapterRegistry;
import io.github.lingjiuu.wire.WireSession;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class RegularTaskStreamRetryTest extends TestCase {

    public void testStreamDisconnectRetriesWithoutCancellingTools() throws Exception {
        AtomicInteger streamAttempts = new AtomicInteger();
        AtomicInteger toolExecutions = new AtomicInteger();
        Tool tool = new RetryableTool(toolExecutions);
        Session session = new SessionFactory(sessionConfig(new RetryableProvider(streamAttempts), tool, null))
                .openSession();

        session.submitAsync(io.github.lingjiuu.input.TurnInput.ofText("run the retryable tool"));
        assertTrue(session.waitForIdle(Duration.ofSeconds(5)));

        assertEquals(2, streamAttempts.get());
        assertEquals(1, toolExecutions.get());
        assertTrue(session.timelineEvents()
                .stream()
                .anyMatch(event -> event.getType() == UiEventType.STREAM_RETRY
                        && "Reconnecting... 1/3".equals(((io.github.lingjiuu.protocol.UiEventPayloads.Text) event.getPayload()).text())));

        ToolResultMessage toolResult = session.messages()
                .stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(message -> "call-1".equals(message.getToolCallId()))
                .findFirst()
                .orElse(null);
        assertNotNull(toolResult);
        assertEquals("tool-ok", MessageContents.text(toolResult));

        String finalAssistantText = session.messages()
                .stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(message -> !message.isError() && !message.isAborted())
                .map(MessageContents::text)
                .reduce((first, second) -> second)
                .orElse("");
        assertEquals("all done", finalAssistantText);

        assertFalse(
                session.messages()
                        .stream()
                        .filter(AssistantMessage.class::isInstance)
                        .map(AssistantMessage.class::cast)
                        .anyMatch(AssistantMessage::isError)
        );
    }

    public void testToolOutcomesAreProcessedBeforeHistoryTranscriptAndNextRequest() throws Exception {
        AtomicInteger streamAttempts = new AtomicInteger();
        List<ModelRequest> requests = Collections.synchronizedList(new ArrayList<>());
        TranscriptStore transcriptStore = new TranscriptStore(Files.createTempDirectory("aether-regular-task-transcript-test"));
        Tool tool = new LargeResultTool();
        Session session = new SessionFactory(sessionConfig(
                new ToolThenDoneProvider(streamAttempts, requests),
                tool,
                transcriptStore
        )).openSession();

        session.submitAsync(io.github.lingjiuu.input.TurnInput.ofText("run the large result tool"));
        assertTrue(session.waitForIdle(Duration.ofSeconds(5)));

        assertEquals(2, streamAttempts.get());
        ToolResultMessage toolResult = session.messages()
                .stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(message -> "call-large".equals(message.getToolCallId()))
                .findFirst()
                .orElse(null);
        assertNotNull(toolResult);
        String processedText = MessageContents.text(toolResult);
        assertTrue(processedText.startsWith(io.github.lingjiuu.tool.result.ToolResultProcessor.PERSISTED_OUTPUT_TAG));
        assertFalse(processedText.equals(LargeResultTool.LARGE_OUTPUT));

        Path artifact = persistedOutputPath(processedText);
        assertTrue(artifact.startsWith(transcriptStore.transcriptsDir()
                .resolve(session.sessionId())
                .resolve("tool-results")));
        assertEquals(LargeResultTool.LARGE_OUTPUT, Files.readString(artifact, StandardCharsets.UTF_8));

        ToolResultMessage transcriptResult = transcriptStore.read(session.sessionId())
                .stream()
                .map(TranscriptRecord::getItem)
                .filter(MessageTranscriptItem.class::isInstance)
                .map(MessageTranscriptItem.class::cast)
                .map(MessageTranscriptItem::getMessage)
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(message -> "call-large".equals(message.getToolCallId()))
                .findFirst()
                .orElse(null);
        assertNotNull(transcriptResult);
        assertEquals(processedText, MessageContents.text(transcriptResult));

        assertEquals(2, requests.size());
        ToolResultMessage secondRequestToolResult = requests.get(1).getMessages()
                .stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(message -> "call-large".equals(message.getToolCallId()))
                .findFirst()
                .orElse(null);
        assertNotNull(secondRequestToolResult);
        assertEquals(processedText, MessageContents.text(secondRequestToolResult));
    }

    private SessionConfig sessionConfig(WireAdapter provider, Tool tool, TranscriptStore transcriptStore) {
        return new SessionConfig(
                new ModelClient(new WireAdapterRegistry().register(provider)),
                "You are a test agent.",
                "",
                "",
                List.of(),
                Path.of(".").toAbsolutePath().normalize(),
                TestModelSelections.fakeSelection(),
                transcriptStore,
                List.of(tool),
                List.of(tool.name()),
                PermissionPreset.FULL_ACCESS
        );
    }

    private Path persistedOutputPath(String text) {
        String marker = "Full output saved to: ";
        int start = text.indexOf(marker);
        assertTrue(start >= 0);
        int pathStart = start + marker.length();
        int pathEnd = text.indexOf('\n', pathStart);
        assertTrue(pathEnd > pathStart);
        return Path.of(text.substring(pathStart, pathEnd));
    }

    private record RetryableProvider(AtomicInteger streamAttempts) implements WireAdapter {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WireSession openSession(ModelSelection selection) {
            return (request, cancellationToken) -> {
                int attempt = streamAttempts.incrementAndGet();
                if (attempt == 1) {
                    return new RetryableFirstStream();
                }
                return new RetryableSecondStream();
            };
        }
    }

    private record ToolThenDoneProvider(
            AtomicInteger streamAttempts,
            List<ModelRequest> requests
    ) implements WireAdapter {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WireSession openSession(ModelSelection selection) {
            return (request, cancellationToken) -> {
                requests.add(request);
                int attempt = streamAttempts.incrementAndGet();
                if (attempt == 1) {
                    return new LargeToolCallStream();
                }
                return new RetryableSecondStream();
            };
        }
    }

    private static final class RetryableFirstStream extends AssistantStream {
        private AssistantMessage result;

        @Override
        public AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TOOLCALL_END)
                    .itemId("item-1")
                    .contentIndex(0)
                    .toolName("retryable")
                    .toolCall(ToolCallContent.builder()
                            .toolCallId("call-1")
                            .toolName("retryable")
                            .argumentsJson("{}")
                            .build())
                    .partial(AssistantMessage.builder()
                            .contents(List.of(ToolCallContent.builder()
                                    .toolCallId("call-1")
                                    .toolName("retryable")
                                    .argumentsJson("{}")
                                    .build()))
                            .stopReason(AssistantMessage.StopReason.TOOLUSE)
                            .build())
                    .build());
            result = AssistantMessage.builder()
                    .stopReason(AssistantMessage.StopReason.ERROR)
                    .errorMessage("stream disconnected before completion: OpenAI stream ended unexpectedly")
                    .contents(List.of(TextContent.builder().text("").build()))
                    .build();
            return result;
        }

        @Override
        public AssistantMessage result() {
            return result;
        }
    }

    private static final class RetryableSecondStream extends AssistantStream {
        private AssistantMessage result;

        @Override
        public AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_START)
                    .itemId("item-2")
                    .contentIndex(0)
                    .build());
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_DELTA)
                    .itemId("item-2")
                    .contentIndex(0)
                    .delta("all ")
                    .build());
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_DELTA)
                    .itemId("item-2")
                    .contentIndex(0)
                    .delta("done")
                    .build());
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_END)
                    .itemId("item-2")
                    .contentIndex(0)
                    .content("all done")
                    .partial(AssistantMessage.builder()
                            .contents(List.of(TextContent.builder().text("all done").build()))
                            .stopReason(AssistantMessage.StopReason.STOP)
                            .build())
                    .build());
            result = AssistantMessage.builder()
                    .stopReason(AssistantMessage.StopReason.STOP)
                    .contents(List.of(TextContent.builder().text("all done").build()))
                    .build();
            return result;
        }

        @Override
        public AssistantMessage result() {
            return result;
        }
    }

    private static final class LargeToolCallStream extends AssistantStream {
        private AssistantMessage result;

        @Override
        public AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
            ToolCallContent toolCall = ToolCallContent.builder()
                    .toolCallId("call-large")
                    .toolName("large-result")
                    .argumentsJson("{}")
                    .build();
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TOOLCALL_END)
                    .itemId("item-large")
                    .contentIndex(0)
                    .toolName("large-result")
                    .toolCall(toolCall)
                    .partial(AssistantMessage.builder()
                            .contents(List.of(toolCall))
                            .stopReason(AssistantMessage.StopReason.TOOLUSE)
                            .build())
                    .build());
            result = AssistantMessage.builder()
                    .stopReason(AssistantMessage.StopReason.TOOLUSE)
                    .contents(List.of(toolCall))
                    .build();
            return result;
        }

        @Override
        public AssistantMessage result() {
            return result;
        }
    }

    private record RetryableTool(AtomicInteger executions) implements Tool {
        @Override
        public String name() {
            return "retryable";
        }

        @Override
        public String label() {
            return "Retryable";
        }

        @Override
        public String description() {
            return "A tool used for stream retry tests.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of()
            );
        }

        @Override
        public ToolRiskLevel riskLevel() {
            return ToolRiskLevel.READ_ONLY;
        }

        @Override
        public ToolExecutionResult execute(ToolInvocation context) {
            executions.incrementAndGet();
            return ToolExecutionResult.text("tool-ok");
        }
    }

    private static final class LargeResultTool implements Tool {
        private static final String LARGE_OUTPUT = "large-result\n" + "x".repeat(60_000);

        @Override
        public String name() {
            return "large-result";
        }

        @Override
        public String label() {
            return "large result";
        }

        @Override
        public String description() {
            return "A tool used for large result processing tests.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of()
            );
        }

        @Override
        public ToolRiskLevel riskLevel() {
            return ToolRiskLevel.READ_ONLY;
        }

        @Override
        public ToolResultPolicy resultPolicy() {
            return ToolResultPolicy.withMaxResultSizeChars(20_000);
        }

        @Override
        public ToolExecutionResult execute(ToolInvocation context) {
            return ToolExecutionResult.text(LARGE_OUTPUT);
        }
    }
}
