package io.github.lingjiuu.agent;

import io.github.lingjiuu.agent.invocation.ModelInvocationResult;
import io.github.lingjiuu.agent.turn.TurnPostprocessor;
import io.github.lingjiuu.agent.turn.TurnResult;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionMode;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import junit.framework.TestCase;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TurnPostprocessorToolSchedulingTest extends TestCase {

    public void testParallelSafeToolCallsOverlapAndResultsStayInSourceOrder() throws Exception {
        SchedulingProbe probe = new SchedulingProbe(2);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BlockingTool("first", ToolExecutionMode.PARALLEL_SAFE, probe));
        registry.register(new BlockingTool("second", ToolExecutionMode.PARALLEL_SAFE, probe));
        TurnPostprocessor postprocessor = new TurnPostprocessor(registry);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TurnResult> future = executor.submit(() -> postprocessor.process(invocationResult("first", "second"), 1));
            assertTrue(probe.entered.await(1, TimeUnit.SECONDS));
            probe.release.countDown();
            TurnResult result = future.get(2, TimeUnit.SECONDS);

            assertEquals(2, probe.maxConcurrent.get());
            assertToolResultOrder(result, "first", "second");
        } finally {
            probe.release.countDown();
            executor.shutdownNow();
        }
    }

    public void testSequentialToolCallSerializesWholeBatch() {
        SchedulingProbe probe = new SchedulingProbe(1);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BlockingTool("first", ToolExecutionMode.SEQUENTIAL, probe));
        registry.register(new BlockingTool("second", ToolExecutionMode.PARALLEL_SAFE, probe));
        TurnPostprocessor postprocessor = new TurnPostprocessor(registry);

        TurnResult result = postprocessor.process(invocationResult("first", "second"), 1);

        assertEquals(1, probe.maxConcurrent.get());
        assertToolResultOrder(result, "first", "second");
    }

    private void assertToolResultOrder(TurnResult result, String first, String second) {
        assertEquals(3, result.appendedMessages().size());
        ToolResultMessage firstResult = (ToolResultMessage) result.appendedMessages().get(1);
        ToolResultMessage secondResult = (ToolResultMessage) result.appendedMessages().get(2);
        assertEquals(first, firstResult.getToolName());
        assertEquals(second, secondResult.getToolName());
        assertEquals(first, MessageContents.text(firstResult));
        assertEquals(second, MessageContents.text(secondResult));
    }

    private ModelInvocationResult invocationResult(String firstTool, String secondTool) {
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .provider("fake")
                .model("test-model")
                .stopReason(AssistantMessage.StopReason.TOOLUSE)
                .contents(List.of(
                        TextContent.builder().text("Calling tools.").build(),
                        toolCall("call-1", firstTool),
                        toolCall("call-2", secondTool)
                ))
                .build();
        return new ModelInvocationResult(
                assistantMessage,
                "Calling tools.",
                MessageContents.toolCalls(assistantMessage),
                List.of()
        );
    }

    private ToolCallContent toolCall(String id, String toolName) {
        return ToolCallContent.builder()
                .toolCallId(id)
                .toolName(toolName)
                .argumentsJson("{}")
                .build();
    }

    private static final class SchedulingProbe {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicInteger currentConcurrent = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();

        private SchedulingProbe(int enterCount) {
            this.entered = new CountDownLatch(enterCount);
            this.release = enterCount > 1 ? new CountDownLatch(1) : new CountDownLatch(0);
        }

        private void enterAndWait() {
            int current = currentConcurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(current, Math::max);
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                currentConcurrent.decrementAndGet();
            }
        }
    }

    private static final class BlockingTool implements ToolDefinition {
        private final String name;
        private final ToolExecutionMode executionMode;
        private final SchedulingProbe probe;

        private BlockingTool(String name, ToolExecutionMode executionMode, SchedulingProbe probe) {
            this.name = name;
            this.executionMode = executionMode;
            this.probe = probe;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String label() {
            return name;
        }

        @Override
        public String description() {
            return "Blocks for scheduling tests.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of("type", "object");
        }

        @Override
        public ToolExecutionMode executionMode() {
            return executionMode;
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            probe.enterAndWait();
            return ToolExecutionResult.text(name);
        }
    }
}
