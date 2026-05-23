package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.input.TurnInput;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.ProviderSession;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.resource.PromptResources;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionBuilder;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.protocol.UiEventPayloads;
import io.github.lingjiuu.protocol.UiEventType;
import io.github.lingjiuu.protocol.UiItemBodies;
import io.github.lingjiuu.protocol.UiItemKind;
import io.github.lingjiuu.protocol.UiToolCall;
import io.github.lingjiuu.protocol.UiToolResult;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class UiEventProtocolTest extends TestCase {

    public void testToolArgumentDeltasHaveStableIdentityAndOrder() {
        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-echo")
                .toolName("echo")
                .argumentsJson("{\"value\":\"ok\"}")
                .build();
        Session session = new SessionBuilder()
                .config(sessionConfig(new StreamingToolProvider(toolCall), new EchoTool()))
                .build();
        List<UiEvent> events = new CopyOnWriteArrayList<>();
        session.subscribe(events::add);

        session.submit(TurnInput.ofText("call echo"));

        UiEvent delta = first(events, UiEventType.TOOL_CALL_ARGUMENTS_DELTA);
        assertNotNull(delta);
        assertTrue(delta.getPayload() instanceof UiEventPayloads.ToolArgumentsDelta);
        UiEventPayloads.ToolArgumentsDelta deltaPayload =
                (UiEventPayloads.ToolArgumentsDelta) delta.getPayload();
        assertEquals("item-tool", deltaPayload.itemId());
        assertEquals("item-tool", deltaPayload.toolCall().getItemId());
        assertEquals(Integer.valueOf(0), deltaPayload.toolCall().getContentIndex());
        assertEquals("call-echo", deltaPayload.toolCall().getToolCallId());
        assertEquals("echo", deltaPayload.toolCall().getToolName());
        assertEquals("{\"value\"", deltaPayload.delta());
        assertNotNull(delta.getSequence());
        assertNotNull(delta.getTimestampMs());

        UiEvent done = first(events, UiEventType.TOOL_CALL_ARGUMENTS_DONE);
        assertNotNull(done);
        assertTrue(done.getPayload() instanceof UiEventPayloads.ToolArgumentsDone);
        UiEventPayloads.ToolArgumentsDone donePayload =
                (UiEventPayloads.ToolArgumentsDone) done.getPayload();
        assertEquals(UiItemKind.TOOL_CALL, donePayload.item().getKind());
        assertTrue(donePayload.item().getBody() instanceof UiItemBodies.ToolCall);
        UiToolCall doneToolCall = ((UiItemBodies.ToolCall) donePayload.item().getBody()).toolCall();
        assertEquals("item-tool", doneToolCall.getItemId());
        assertEquals(Integer.valueOf(0), doneToolCall.getContentIndex());
        assertEquals("call-echo", doneToolCall.getToolCallId());
        assertEquals("{\"value\":\"ok\"}", doneToolCall.getArgumentsJson());

        UiEvent executionBegin = first(events, UiEventType.TOOL_EXECUTION_BEGIN);
        assertNotNull(executionBegin);
        assertTrue(executionBegin.getPayload() instanceof UiEventPayloads.ToolExecution);
        UiEventPayloads.ToolExecution beginPayload =
                (UiEventPayloads.ToolExecution) executionBegin.getPayload();
        assertEquals("item-tool", beginPayload.toolCall().getItemId());
        assertEquals("RUNNING", beginPayload.toolResult().getStatus());

        UiEvent toolResult = first(events, UiEventType.TOOL_RESULT);
        assertNotNull(toolResult);
        assertTrue(toolResult.getPayload() instanceof UiEventPayloads.ToolResult);
        UiEventPayloads.ToolResult resultPayload =
                (UiEventPayloads.ToolResult) toolResult.getPayload();
        assertTrue(resultPayload.item().getBody() instanceof UiItemBodies.ToolResult);
        UiToolResult result = ((UiItemBodies.ToolResult) resultPayload.item().getBody()).toolResult();
        assertEquals("item-tool", result.getSourceItemId());
        assertEquals(Integer.valueOf(0), result.getContentIndex());
        assertEquals("COMPLETED", result.getStatus());
        assertNotNull(result.getDurationMs());

        assertTrue(indexOf(events, UiEventType.ITEM_STARTED, UiItemKind.TOOL_CALL)
                < indexOf(events, UiEventType.TOOL_CALL_ARGUMENTS_DELTA, UiItemKind.TOOL_CALL));
        assertTrue(indexOf(events, UiEventType.TOOL_CALL_ARGUMENTS_DELTA, UiItemKind.TOOL_CALL)
                < indexOf(events, UiEventType.TOOL_CALL_ARGUMENTS_DONE, UiItemKind.TOOL_CALL));
        assertTrue(indexOf(events, UiEventType.TOOL_CALL_ARGUMENTS_DONE, UiItemKind.TOOL_CALL)
                < indexOf(events, UiEventType.ITEM_COMPLETED, UiItemKind.TOOL_CALL));
        assertTrue(indexOf(events, UiEventType.ITEM_COMPLETED, UiItemKind.TOOL_CALL)
                < indexOf(events, UiEventType.TOOL_EXECUTION_BEGIN, null));
        assertNotNull(first(events, UiEventType.TURN_COMPLETED));
        assertMonotonicSequences(events);
    }

    public void testDeniedToolEmitsDeclinedOutcomeWithoutExecutingToolBody() {
        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-write")
                .toolName("write_echo")
                .argumentsJson("{\"value\":\"nope\"}")
                .build();
        AtomicInteger executeCount = new AtomicInteger();
        Session session = new SessionBuilder()
                .config(sessionConfig(new StreamingToolProvider(toolCall), new ApprovalRequiredTool(executeCount)))
                .build();
        List<UiEvent> events = new CopyOnWriteArrayList<>();
        session.subscribe(events::add);

        session.submit(TurnInput.ofText("call write echo"));

        assertNotNull(first(events, UiEventType.TOOL_CALL));
        assertNotNull(first(events, UiEventType.TOOL_EXECUTION_BEGIN));
        assertNotNull(first(events, UiEventType.APPROVAL_REQUESTED));
        assertNotNull(first(events, UiEventType.APPROVAL_RESOLVED));
        assertEquals(0, executeCount.get());

        UiEvent toolExecutionEnd = first(events, UiEventType.TOOL_EXECUTION_END);
        assertNotNull(toolExecutionEnd);
        UiEventPayloads.ToolExecution endPayload =
                (UiEventPayloads.ToolExecution) toolExecutionEnd.getPayload();
        assertEquals("DECLINED", endPayload.toolResult().getStatus());

        UiEvent toolResult = first(events, UiEventType.TOOL_RESULT);
        assertNotNull(toolResult);
        UiEventPayloads.ToolResult resultPayload =
                (UiEventPayloads.ToolResult) toolResult.getPayload();
        UiToolResult result = ((UiItemBodies.ToolResult) resultPayload.item().getBody()).toolResult();
        assertEquals("DECLINED", result.getStatus());
        assertNotNull(first(events, UiEventType.TURN_COMPLETED));
    }

    private SessionConfig sessionConfig(Provider provider, ToolDefinition tool) {
        return new SessionConfig(
                new LlmClient(new ProviderRegistry().register(provider)),
                "You are a test agent.",
                Path.of(".").toAbsolutePath().normalize(),
                LlmModel.builder()
                        .id("fake-model")
                        .api("fake")
                        .provider("fake")
                        .input(List.of("text"))
                        .contextWindowTokens(100_000L)
                        .build(),
                RequestAuth.ok("test", Map.of()),
                null,
                null,
                List.of(tool),
                PromptResources.empty(),
                List.of(tool.name())
        );
    }

    private UiEvent first(List<UiEvent> events, UiEventType type) {
        return events.stream()
                .filter(event -> event.getType() == type)
                .findFirst()
                .orElse(null);
    }

    private int indexOf(List<UiEvent> events, UiEventType type, UiItemKind itemKind) {
        for (int index = 0; index < events.size(); index++) {
            UiEvent event = events.get(index);
            if (event.getType() == type && (itemKind == null || itemKind(event) == itemKind)) {
                return index;
            }
        }
        return -1;
    }

    private UiItemKind itemKind(UiEvent event) {
        if (event.getPayload() instanceof UiEventPayloads.ItemStarted itemStarted) {
            return itemStarted.itemKind();
        }
        if (event.getPayload() instanceof UiEventPayloads.ItemCompleted itemCompleted) {
            return itemCompleted.item() == null ? null : itemCompleted.item().getKind();
        }
        if (event.getPayload() instanceof UiEventPayloads.TextDelta textDelta) {
            return textDelta.itemKind();
        }
        if (event.getPayload() instanceof UiEventPayloads.ToolArgumentsDelta) {
            return UiItemKind.TOOL_CALL;
        }
        if (event.getPayload() instanceof UiEventPayloads.ToolArgumentsDone toolDone) {
            return toolDone.item() == null ? null : toolDone.item().getKind();
        }
        return null;
    }

    private void assertMonotonicSequences(List<UiEvent> events) {
        long previous = 0;
        for (UiEvent event : events) {
            assertNotNull(event.getSequence());
            assertTrue(event.getSequence() > previous);
            previous = event.getSequence();
        }
    }

    private static final class StreamingToolProvider implements Provider {
        private static final String ITEM_TOOL = "item-tool";
        private static final String ITEM_FINAL = "item-final";

        private final ToolCallContent toolCall;
        private final AtomicInteger requestCount = new AtomicInteger();

        private StreamingToolProvider(ToolCallContent toolCall) {
            this.toolCall = toolCall;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public ProviderSession openSession(LlmModel model, RequestAuth auth) {
            return (request, cancellationToken) -> new StreamingToolStream(
                    toolCall,
                    requestCount.incrementAndGet()
            );
        }
    }

    private static final class StreamingToolStream extends AssistantStream {
        private final ToolCallContent toolCall;
        private final int requestIndex;
        private AssistantMessage result;

        private StreamingToolStream(ToolCallContent toolCall, int requestIndex) {
            this.toolCall = toolCall;
            this.requestIndex = requestIndex;
        }

        @Override
        public AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
            if (requestIndex == 1) {
                emitToolCall(consumer);
            } else {
                emitFinalText(consumer);
            }
            return result;
        }

        @Override
        public AssistantMessage result() {
            return result;
        }

        private void emitToolCall(Consumer<AssistantStreamEvent> consumer) {
            ToolCallContent partialToolCall = copyToolCall("{\"value\"");
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TOOLCALL_START)
                    .itemId(StreamingToolProvider.ITEM_TOOL)
                    .toolCallId(toolCall.getToolCallId())
                    .toolName(toolCall.getToolName())
                    .contentIndex(0)
                    .toolCall(partialToolCall)
                    .partial(toolCallMessage(partialToolCall))
                    .build());
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TOOLCALL_DELTA)
                    .itemId(StreamingToolProvider.ITEM_TOOL)
                    .toolCallId(toolCall.getToolCallId())
                    .toolName(toolCall.getToolName())
                    .contentIndex(0)
                    .delta("{\"value\"")
                    .toolCall(partialToolCall)
                    .partial(toolCallMessage(partialToolCall))
                    .build());
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TOOLCALL_DELTA)
                    .itemId(StreamingToolProvider.ITEM_TOOL)
                    .toolCallId(toolCall.getToolCallId())
                    .toolName(toolCall.getToolName())
                    .contentIndex(0)
                    .delta(":\"ok\"}")
                    .toolCall(toolCall)
                    .partial(toolCallMessage(toolCall))
                    .build());
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TOOLCALL_END)
                    .itemId(StreamingToolProvider.ITEM_TOOL)
                    .toolCallId(toolCall.getToolCallId())
                    .toolName(toolCall.getToolName())
                    .contentIndex(0)
                    .toolCall(toolCall)
                    .partial(toolCallMessage(toolCall))
                    .build());
            result = toolCallMessage(toolCall);
        }

        private void emitFinalText(Consumer<AssistantStreamEvent> consumer) {
            AssistantMessage partial = AssistantMessage.builder()
                    .contents(List.of(TextContent.builder().text("done").build()))
                    .stopReason(AssistantMessage.StopReason.STOP)
                    .build();
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_START)
                    .itemId(StreamingToolProvider.ITEM_FINAL)
                    .contentIndex(0)
                    .partial(partial)
                    .build());
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_DELTA)
                    .itemId(StreamingToolProvider.ITEM_FINAL)
                    .contentIndex(0)
                    .delta("done")
                    .partial(partial)
                    .build());
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_END)
                    .itemId(StreamingToolProvider.ITEM_FINAL)
                    .contentIndex(0)
                    .content("done")
                    .partial(partial)
                    .build());
            result = partial;
        }

        private AssistantMessage toolCallMessage(ToolCallContent toolCall) {
            return AssistantMessage.builder()
                    .contents(List.of(toolCall))
                    .stopReason(AssistantMessage.StopReason.TOOLUSE)
                    .build();
        }

        private ToolCallContent copyToolCall(String argumentsJson) {
            return ToolCallContent.builder()
                    .toolCallId(toolCall.getToolCallId())
                    .toolName(toolCall.getToolName())
                    .argumentsJson(argumentsJson)
                    .build();
        }
    }

    private record EchoTool() implements ToolDefinition {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String label() {
            return "Echo";
        }

        @Override
        public String description() {
            return "Echoes test input.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "value", Map.of("type", "string")
                    ),
                    "required", List.of("value")
            );
        }

        @Override
        public ToolRiskLevel riskLevel() {
            return ToolRiskLevel.READ_ONLY;
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context) {
            return ToolExecutionResult.text("tool ok");
        }
    }

    private record ApprovalRequiredTool(AtomicInteger executeCount) implements ToolDefinition {
        @Override
        public String name() {
            return "write_echo";
        }

        @Override
        public String label() {
            return "Write Echo";
        }

        @Override
        public String description() {
            return "Requires approval before echoing test input.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "value", Map.of("type", "string")
                    ),
                    "required", List.of("value")
            );
        }

        @Override
        public ToolRiskLevel riskLevel() {
            return ToolRiskLevel.WRITE;
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context) {
            executeCount.incrementAndGet();
            return ToolExecutionResult.text("should not run");
        }
    }
}
