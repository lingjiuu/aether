package io.github.lingjiuu.session.task;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.ModelInfo;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.model.client.AssistantStream;
import io.github.lingjiuu.model.client.AssistantStreamEvent;
import io.github.lingjiuu.model.client.ModelErrorCode;
import io.github.lingjiuu.model.client.ModelErrorInfo;
import io.github.lingjiuu.model.client.ModelInvocationException;
import io.github.lingjiuu.model.client.ModelClient;
import io.github.lingjiuu.model.client.ModelRequest;
import io.github.lingjiuu.model.client.ModelRetryOptions;
import io.github.lingjiuu.protocol.UiEventPayloads;
import io.github.lingjiuu.protocol.UiEventType;
import io.github.lingjiuu.protocol.UiItemBodies;
import io.github.lingjiuu.protocol.UiToolResult;
import io.github.lingjiuu.provider.ProviderAuth;
import io.github.lingjiuu.provider.ProviderEndpoint;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolCallResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.permission.ApprovalResponse;
import io.github.lingjiuu.tool.permission.PermissionPreset;
import io.github.lingjiuu.tool.result.ModelToolResult;
import io.github.lingjiuu.tool.result.ToolResultContext;
import io.github.lingjiuu.tool.result.ToolResultPolicy;
import io.github.lingjiuu.tool.result.ToolResultProcessor;
import io.github.lingjiuu.transcript.TranscriptRecord;
import io.github.lingjiuu.transcript.TranscriptStore;
import io.github.lingjiuu.transcript.item.MessageTranscriptItem;
import io.github.lingjiuu.transcript.item.ToolResultReplacementTranscriptItem;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class RegularTaskStreamRetryTest extends TestCase {

    private static final ModelRetryOptions FAST_RETRY_OPTIONS = new ModelRetryOptions(4, 5, 1, 1, 0);

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
                        && "Reconnecting... 1/5".equals(((io.github.lingjiuu.protocol.UiEventPayloads.Text) event.getPayload()).text())));

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

    public void testAggregateToolBudgetIsAppliedBeforeNextRequestWithAppendOnlyReplacement() throws Exception {
        AtomicInteger streamAttempts = new AtomicInteger();
        List<ModelRequest> requests = Collections.synchronizedList(new ArrayList<>());
        TranscriptStore transcriptStore = new TranscriptStore(Files.createTempDirectory("aether-aggregate-budget-transcript-test"));
        Tool tool = new MediumResultTool();
        Session session = new SessionFactory(sessionConfig(
                new MediumBatchThenDoneProvider(streamAttempts, requests),
                tool,
                transcriptStore
        )).openSession();

        session.submitAsync(io.github.lingjiuu.input.TurnInput.ofText("run the medium result tools"));
        assertTrue(session.waitForIdle(Duration.ofSeconds(5)));

        assertEquals(2, streamAttempts.get());
        assertEquals(2, requests.size());
        List<ToolResultMessage> transcriptToolResults = transcriptStore.read(session.sessionId())
                .stream()
                .map(TranscriptRecord::getItem)
                .filter(MessageTranscriptItem.class::isInstance)
                .map(MessageTranscriptItem.class::cast)
                .map(MessageTranscriptItem::getMessage)
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(message -> "batch-medium".equals(message.getToolBatchId()))
                .toList();
        assertEquals(5, transcriptToolResults.size());
        assertTrue(transcriptToolResults.stream()
                .allMatch(message -> !MessageContents.text(message).startsWith(ToolResultProcessor.PERSISTED_OUTPUT_TAG)));

        List<ToolResultReplacementTranscriptItem> replacementItems = transcriptStore.read(session.sessionId())
                .stream()
                .map(TranscriptRecord::getItem)
                .filter(ToolResultReplacementTranscriptItem.class::isInstance)
                .map(ToolResultReplacementTranscriptItem.class::cast)
                .filter(item -> "batch-medium".equals(item.getToolBatchId()))
                .toList();
        assertTrue("expected aggregate replacement transcript item", replacementItems.size() >= 1);

        List<ToolResultMessage> secondRequestToolResults = requests.get(1).getMessages()
                .stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(message -> "batch-medium".equals(message.getToolBatchId()))
                .toList();
        assertEquals(5, secondRequestToolResults.size());
        assertTrue(secondRequestToolResults.stream()
                .anyMatch(message -> MessageContents.text(message).startsWith(ToolResultProcessor.PERSISTED_OUTPUT_TAG)));
        long modelVisibleChars = secondRequestToolResults.stream()
                .mapToLong(message -> MessageContents.text(message).length())
                .sum();
        assertTrue("model-visible chars should be under aggregate budget: " + modelVisibleChars,
                modelVisibleChars <= io.github.lingjiuu.tool.result.ToolResultLimits.MAX_TOOL_RESULTS_PER_BATCH_CHARS);
    }

    public void testAggregateToolBudgetRunsBeforeMidTurnAutoCompact() throws Exception {
        AtomicInteger streamAttempts = new AtomicInteger();
        List<ModelRequest> requests = Collections.synchronizedList(new ArrayList<>());
        Tool tool = new MediumResultTool();
        Session session = new SessionFactory(sessionConfig(
                new MediumBatchThenDoneProvider(streamAttempts, requests),
                tool,
                null,
                FAST_RETRY_OPTIONS,
                52_000L
        )).openSession();

        session.submitAsync(io.github.lingjiuu.input.TurnInput.ofText("run the medium result tools"));
        assertTrue(session.waitForIdle(Duration.ofSeconds(5)));

        assertEquals(2, requests.size());
        assertFalse(session.timelineEvents()
                .stream()
                .anyMatch(event -> event.getType() == UiEventType.COMPACT_STARTED));
    }

    public void testToolResultsFlushInToolCallOrderBeforeLaterToolFinishes() throws Exception {
        AtomicInteger streamAttempts = new AtomicInteger();
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        Session session = new SessionFactory(sessionConfig(
                new OrderedFlushThenDoneProvider(streamAttempts),
                new OrderedFlushTool(firstDone, secondStarted, releaseSecond),
                null
        )).openSession();

        try {
            session.submitAsync(io.github.lingjiuu.input.TurnInput.ofText("run ordered tools"));
            assertTrue(firstDone.await(2, TimeUnit.SECONDS));
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            assertTrue(waitForToolResult(session, "call-first", Duration.ofSeconds(2)));
            assertFalse(hasToolResult(session, "call-second"));
        } finally {
            releaseSecond.countDown();
        }

        assertTrue(session.waitForIdle(Duration.ofSeconds(5)));
        List<String> toolCallIds = session.messages()
                .stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .map(ToolResultMessage::getToolCallId)
                .filter(id -> "call-first".equals(id) || "call-second".equals(id))
                .toList();
        assertEquals(List.of("call-first", "call-second"), toolCallIds);
        assertEquals(2, streamAttempts.get());
    }

    public void testApprovalWaitDoesNotBlockIndependentToolUiCompletion() throws Exception {
        AtomicInteger streamAttempts = new AtomicInteger();
        CountDownLatch approvalRequested = new CountDownLatch(1);
        CountDownLatch releaseApproval = new CountDownLatch(1);
        Session session = new SessionFactory(sessionConfig(
                new ApprovalParallelThenDoneProvider(streamAttempts),
                List.of(new ApprovalGatedTool(), new FastReadOnlyTool()),
                null,
                FAST_RETRY_OPTIONS,
                null,
                PermissionPreset.DEFAULT
        )).openSession();
        session.setApprovalHandler(request -> {
            approvalRequested.countDown();
            try {
                releaseApproval.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ApprovalResponse.deny(request.id(), "interrupted");
            }
            return ApprovalResponse.approve(request.id());
        });

        try {
            session.submitAsync(io.github.lingjiuu.input.TurnInput.ofText("run approval and fast tools"));
            assertTrue(approvalRequested.await(2, TimeUnit.SECONDS));
            assertTrue(waitForUiToolResult(session, "call-fast", Duration.ofSeconds(2)));
            assertFalse(hasToolResult(session, "call-fast"));
        } finally {
            releaseApproval.countDown();
        }

        assertTrue(session.waitForIdle(Duration.ofSeconds(5)));
        List<String> toolCallIds = session.messages()
                .stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .map(ToolResultMessage::getToolCallId)
                .filter(id -> "call-approval".equals(id) || "call-fast".equals(id))
                .toList();
        assertEquals(List.of("call-approval", "call-fast"), toolCallIds);
        UiToolResult approvalResult = findUiToolResult(session, "call-approval");
        assertNotNull(approvalResult);
        assertNotNull(approvalResult.getDurationMs());
        assertNotNull(approvalResult.getApprovalWaitMs());
        assertNotNull(approvalResult.getExecutionDurationMs());
        assertTrue(approvalResult.getApprovalWaitMs() > 0);
        assertTrue(approvalResult.getExecutionDurationMs() >= 0);
        assertTrue(approvalResult.getDurationMs()
                >= approvalResult.getApprovalWaitMs() + approvalResult.getExecutionDurationMs());
        assertEquals(2, streamAttempts.get());
    }

    public void testRequestFailureRetriesWithRequestBudget() throws Exception {
        AtomicInteger streamAttempts = new AtomicInteger();
        Tool tool = new RetryableTool(new AtomicInteger());
        Session session = new SessionFactory(sessionConfig(
                new RequestRetryProvider(streamAttempts),
                tool,
                null
        )).openSession();

        session.submitAsync(io.github.lingjiuu.input.TurnInput.ofText("recover from request failure"));
        assertTrue(session.waitForIdle(Duration.ofSeconds(5)));

        assertEquals(2, streamAttempts.get());
        assertTrue(session.timelineEvents()
                .stream()
                .anyMatch(event -> event.getType() == UiEventType.STREAM_RETRY
                        && "Reconnecting... 1/4".equals(((io.github.lingjiuu.protocol.UiEventPayloads.Text) event.getPayload()).text())));
        assertEquals("all done", session.messages()
                .stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .filter(message -> !message.isError() && !message.isAborted())
                .map(MessageContents::text)
                .reduce((first, second) -> second)
                .orElse(""));
    }

    public void testStreamRetryCanBeDisabledByConfig() throws Exception {
        AtomicInteger streamAttempts = new AtomicInteger();
        AtomicInteger toolExecutions = new AtomicInteger();
        Tool tool = new RetryableTool(toolExecutions);
        Session session = new SessionFactory(sessionConfig(
                new RetryableProvider(streamAttempts),
                tool,
                null,
                new ModelRetryOptions(4, 0, 1, 1, 0)
        )).openSession();

        session.submitAsync(io.github.lingjiuu.input.TurnInput.ofText("do not retry stream"));
        assertTrue(session.waitForIdle(Duration.ofSeconds(5)));

        assertEquals(1, streamAttempts.get());
        assertEquals(1, toolExecutions.get());
        assertFalse(session.timelineEvents()
                .stream()
                .anyMatch(event -> event.getType() == UiEventType.STREAM_RETRY));
        assertTrue(session.messages()
                .stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .anyMatch(AssistantMessage::isError));
    }

    private SessionConfig sessionConfig(WireAdapter provider, Tool tool, TranscriptStore transcriptStore) {
        return sessionConfig(provider, tool, transcriptStore, FAST_RETRY_OPTIONS);
    }

    private SessionConfig sessionConfig(
            WireAdapter provider,
            Tool tool,
            TranscriptStore transcriptStore,
            ModelRetryOptions retryOptions
    ) {
        return sessionConfig(provider, tool, transcriptStore, retryOptions, null);
    }

    private SessionConfig sessionConfig(
            WireAdapter provider,
            Tool tool,
            TranscriptStore transcriptStore,
            ModelRetryOptions retryOptions,
            Long autoCompactTokenLimit
    ) {
        return sessionConfig(
                provider,
                List.of(tool),
                transcriptStore,
                retryOptions,
                autoCompactTokenLimit,
                PermissionPreset.FULL_ACCESS
        );
    }

    private SessionConfig sessionConfig(
            WireAdapter provider,
            List<Tool> tools,
            TranscriptStore transcriptStore,
            ModelRetryOptions retryOptions,
            Long autoCompactTokenLimit,
            PermissionPreset permissionPreset
    ) {
        return new SessionConfig(
                new ModelClient(new WireAdapterRegistry().register(provider)),
                "You are a test agent.",
                "",
                "",
                List.of(),
                Path.of(".").toAbsolutePath().normalize(),
                fakeSelection(retryOptions, autoCompactTokenLimit),
                transcriptStore,
                tools,
                tools.stream().map(Tool::name).toList(),
                permissionPreset
        );
    }

    private ModelSelection fakeSelection(ModelRetryOptions retryOptions) {
        return fakeSelection(retryOptions, null);
    }

    private ModelSelection fakeSelection(ModelRetryOptions retryOptions, Long autoCompactTokenLimit) {
        return new ModelSelection(
                ModelInfo.builder()
                        .id("fake-model")
                        .input(List.of("text"))
                        .contextWindowTokens(100_000L)
                        .autoCompactTokenLimit(autoCompactTokenLimit)
                        .build(),
                new ProviderEndpoint("fake", "fake", "http://fake.test/v1", Map.of(), retryOptions),
                ProviderAuth.ok("test", Map.of()),
                null
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

    private boolean waitForToolResult(Session session, String toolCallId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (hasToolResult(session, toolCallId)) {
                return true;
            }
            Thread.sleep(10);
        }
        return hasToolResult(session, toolCallId);
    }

    private boolean hasToolResult(Session session, String toolCallId) {
        return session.messages()
                .stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .anyMatch(message -> toolCallId.equals(message.getToolCallId()));
    }

    private boolean waitForUiToolResult(Session session, String toolCallId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (hasUiToolResult(session, toolCallId)) {
                return true;
            }
            Thread.sleep(10);
        }
        return hasUiToolResult(session, toolCallId);
    }

    private boolean hasUiToolResult(Session session, String toolCallId) {
        return findUiToolResult(session, toolCallId) != null;
    }

    private UiToolResult findUiToolResult(Session session, String toolCallId) {
        return session.timelineEvents()
                .stream()
                .filter(event -> event.getType() == UiEventType.TOOL_RESULT)
                .map(event -> event.getPayload())
                .filter(UiEventPayloads.ToolResult.class::isInstance)
                .map(UiEventPayloads.ToolResult.class::cast)
                .map(UiEventPayloads.ToolResult::item)
                .filter(item -> item.getBody() instanceof UiItemBodies.ToolResult)
                .map(item -> (UiItemBodies.ToolResult) item.getBody())
                .map(UiItemBodies.ToolResult::toolResult)
                .filter(result -> result != null && toolCallId.equals(result.getToolCallId()))
                .findFirst()
                .orElse(null);
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

    private record MediumBatchThenDoneProvider(
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
                    return new MediumBatchToolCallStream();
                }
                return new RetryableSecondStream();
            };
        }
    }

    private record OrderedFlushThenDoneProvider(AtomicInteger streamAttempts) implements WireAdapter {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WireSession openSession(ModelSelection selection) {
            return (request, cancellationToken) -> {
                int attempt = streamAttempts.incrementAndGet();
                if (attempt == 1) {
                    return new OrderedFlushToolCallStream();
                }
                return new RetryableSecondStream();
            };
        }
    }

    private record ApprovalParallelThenDoneProvider(AtomicInteger streamAttempts) implements WireAdapter {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WireSession openSession(ModelSelection selection) {
            return (request, cancellationToken) -> {
                int attempt = streamAttempts.incrementAndGet();
                if (attempt == 1) {
                    return new ApprovalParallelToolCallStream();
                }
                return new RetryableSecondStream();
            };
        }
    }

    private record RequestRetryProvider(AtomicInteger streamAttempts) implements WireAdapter {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WireSession openSession(ModelSelection selection) {
            return (request, cancellationToken) -> {
                int attempt = streamAttempts.incrementAndGet();
                if (attempt == 1) {
                    throw new ModelInvocationException(
                            ModelErrorInfo.of(ModelErrorCode.HTTP_5XX, "synthetic server error"),
                            null
                    );
                }
                return new RetryableSecondStream();
            };
        }
    }

    private static final class MediumBatchToolCallStream extends AssistantStream {
        private AssistantMessage result;

        @Override
        public AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
            List<MessageContent> toolCalls = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                ToolCallContent toolCall = ToolCallContent.builder()
                        .toolCallId("call-medium-" + i)
                        .toolBatchId("batch-medium")
                        .toolName("medium-result")
                        .argumentsJson("{}")
                        .build();
                toolCalls.add(toolCall);
                consumer.accept(AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.TOOLCALL_END)
                        .itemId("item-medium-" + i)
                        .contentIndex(i - 1)
                        .toolName("medium-result")
                        .toolCall(toolCall)
                        .partial(AssistantMessage.builder()
                                .contents(List.copyOf(toolCalls))
                                .stopReason(AssistantMessage.StopReason.TOOLUSE)
                                .build())
                        .build());
            }
            result = AssistantMessage.builder()
                    .stopReason(AssistantMessage.StopReason.TOOLUSE)
                    .contents(List.copyOf(toolCalls))
                    .build();
            return result;
        }

        @Override
        public AssistantMessage result() {
            return result;
        }
    }

    private static final class OrderedFlushToolCallStream extends AssistantStream {
        private AssistantMessage result;

        @Override
        public AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
            ToolCallContent first = orderedToolCall("call-first");
            ToolCallContent second = orderedToolCall("call-second");
            consumer.accept(toolCallEnd("item-first", 0, first, List.of(first)));
            consumer.accept(toolCallEnd("item-second", 1, second, List.of(first, second)));
            result = AssistantMessage.builder()
                    .stopReason(AssistantMessage.StopReason.TOOLUSE)
                    .contents(List.of(first, second))
                    .build();
            return result;
        }

        @Override
        public AssistantMessage result() {
            return result;
        }

        private ToolCallContent orderedToolCall(String toolCallId) {
            return ToolCallContent.builder()
                    .toolCallId(toolCallId)
                    .toolBatchId("batch-ordered")
                    .toolName("ordered-flush")
                    .argumentsJson("{}")
                    .build();
        }

        private AssistantStreamEvent toolCallEnd(
                String itemId,
                int contentIndex,
                ToolCallContent toolCall,
                List<MessageContent> partialToolCalls
        ) {
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TOOLCALL_END)
                    .itemId(itemId)
                    .contentIndex(contentIndex)
                    .toolName("ordered-flush")
                    .toolCall(toolCall)
                    .partial(AssistantMessage.builder()
                            .contents(partialToolCalls)
                            .stopReason(AssistantMessage.StopReason.TOOLUSE)
                            .build())
                    .build();
        }
    }

    private static final class ApprovalParallelToolCallStream extends AssistantStream {
        private AssistantMessage result;

        @Override
        public AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
            ToolCallContent approval = ToolCallContent.builder()
                    .toolCallId("call-approval")
                    .toolBatchId("batch-approval-parallel")
                    .toolName("approval-gated")
                    .argumentsJson("{}")
                    .build();
            ToolCallContent fast = ToolCallContent.builder()
                    .toolCallId("call-fast")
                    .toolBatchId("batch-approval-parallel")
                    .toolName("fast-read-only")
                    .argumentsJson("{}")
                    .build();
            consumer.accept(toolCallEnd("item-approval", 0, approval, List.of(approval)));
            consumer.accept(toolCallEnd("item-fast", 1, fast, List.of(approval, fast)));
            result = AssistantMessage.builder()
                    .stopReason(AssistantMessage.StopReason.TOOLUSE)
                    .contents(List.of(approval, fast))
                    .build();
            return result;
        }

        @Override
        public AssistantMessage result() {
            return result;
        }

        private AssistantStreamEvent toolCallEnd(
                String itemId,
                int contentIndex,
                ToolCallContent toolCall,
                List<MessageContent> partialToolCalls
        ) {
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TOOLCALL_END)
                    .itemId(itemId)
                    .contentIndex(contentIndex)
                    .toolName(toolCall.getToolName())
                    .toolCall(toolCall)
                    .partial(AssistantMessage.builder()
                            .contents(partialToolCalls)
                            .stopReason(AssistantMessage.StopReason.TOOLUSE)
                            .build())
                    .build();
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
                    .errorInfo(ModelErrorInfo.of(
                            ModelErrorCode.STREAM_DISCONNECTED,
                            "stream disconnected before completion: OpenAI stream ended unexpectedly"
                    ))
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

    private record RetryableTool(AtomicInteger executions) implements Tool<Object, String> {
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
        public Map<String, Object> inputSchema() {
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
        public Object parseInput(String argumentsJson) {
            return new Object();
        }

        @Override
        public ToolCallResult<String> call(Object input, io.github.lingjiuu.tool.ToolUseContext context) {
            executions.incrementAndGet();
            return ToolCallResult.success("tool-ok");
        }

        @Override
        public ModelToolResult toModelResult(String output, ToolResultContext<Object, String> context) {
            return ModelToolResult.text(output);
        }
    }

    private static final class LargeResultTool implements Tool<Object, String> {
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
        public Map<String, Object> inputSchema() {
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
        public Object parseInput(String argumentsJson) {
            return new Object();
        }

        @Override
        public ToolCallResult<String> call(Object input, io.github.lingjiuu.tool.ToolUseContext context) {
            return ToolCallResult.success(LARGE_OUTPUT);
        }

        @Override
        public ModelToolResult toModelResult(String output, ToolResultContext<Object, String> context) {
            return ModelToolResult.text(output);
        }
    }

    private static final class MediumResultTool implements Tool<Object, String> {
        @Override
        public String name() {
            return "medium-result";
        }

        @Override
        public String label() {
            return "medium result";
        }

        @Override
        public String description() {
            return "A tool used for aggregate result budget tests.";
        }

        @Override
        public Map<String, Object> inputSchema() {
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
        public Object parseInput(String argumentsJson) {
            return new Object();
        }

        @Override
        public ToolCallResult<String> call(Object input, io.github.lingjiuu.tool.ToolUseContext context) {
            String suffix = context == null ? "unknown" : context.toolCallId();
            return ToolCallResult.success((suffix + "\n").repeat(3_000));
        }

        @Override
        public ModelToolResult toModelResult(String output, ToolResultContext<Object, String> context) {
            return ModelToolResult.text(output);
        }
    }

    private static final class OrderedFlushTool implements Tool<Object, String> {
        private final CountDownLatch firstDone;
        private final CountDownLatch secondStarted;
        private final CountDownLatch releaseSecond;

        private OrderedFlushTool(
                CountDownLatch firstDone,
                CountDownLatch secondStarted,
                CountDownLatch releaseSecond
        ) {
            this.firstDone = firstDone;
            this.secondStarted = secondStarted;
            this.releaseSecond = releaseSecond;
        }

        @Override
        public String name() {
            return "ordered-flush";
        }

        @Override
        public String label() {
            return "ordered flush";
        }

        @Override
        public String description() {
            return "A tool used for ordered flush tests.";
        }

        @Override
        public Map<String, Object> inputSchema() {
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
        public Object parseInput(String argumentsJson) {
            return new Object();
        }

        @Override
        public ToolCallResult<String> call(Object input, io.github.lingjiuu.tool.ToolUseContext context) {
            if ("call-second".equals(context.toolCallId())) {
                secondStarted.countDown();
                try {
                    releaseSecond.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolCallResult.failedOutput("interrupted");
                }
                return ToolCallResult.success("second");
            }
            firstDone.countDown();
            return ToolCallResult.success("first");
        }

        @Override
        public ModelToolResult toModelResult(String output, ToolResultContext<Object, String> context) {
            return ModelToolResult.text(output);
        }
    }

    private static final class ApprovalGatedTool implements Tool<Object, String> {
        @Override
        public String name() {
            return "approval-gated";
        }

        @Override
        public String label() {
            return "approval gated";
        }

        @Override
        public String description() {
            return "A tool used for approval ordering tests.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of()
            );
        }

        @Override
        public ToolRiskLevel riskLevel() {
            return ToolRiskLevel.EXEC;
        }

        @Override
        public Object parseInput(String argumentsJson) {
            return new Object();
        }

        @Override
        public ToolCallResult<String> call(Object input, io.github.lingjiuu.tool.ToolUseContext context) {
            return ToolCallResult.success("approval-ok");
        }

        @Override
        public ModelToolResult toModelResult(String output, ToolResultContext<Object, String> context) {
            return ModelToolResult.text(output);
        }
    }

    private static final class FastReadOnlyTool implements Tool<Object, String> {
        @Override
        public String name() {
            return "fast-read-only";
        }

        @Override
        public String label() {
            return "fast read only";
        }

        @Override
        public String description() {
            return "A tool used for parallel UI completion tests.";
        }

        @Override
        public Map<String, Object> inputSchema() {
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
        public Object parseInput(String argumentsJson) {
            return new Object();
        }

        @Override
        public ToolCallResult<String> call(Object input, io.github.lingjiuu.tool.ToolUseContext context) {
            return ToolCallResult.success("fast-ok");
        }

        @Override
        public ModelToolResult toModelResult(String output, ToolResultContext<Object, String> context) {
            return ModelToolResult.text(output);
        }
    }
}
