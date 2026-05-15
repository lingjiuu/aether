package io.github.lingjiuu.tool.runtime;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolCancelledException;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
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
import java.util.concurrent.atomic.AtomicBoolean;

public class ToolCancellationTest extends TestCase {

    public void testCancellationTokenReachesToolAndRunsCallbacks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean callbackRan = new AtomicBoolean(false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WaitingTool(entered, release, callbackRan));
        ToolCancellationSource source = new ToolCancellationSource();
        ToolRunner runner = new ToolRunner(registry);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResultMessage> future = executor.submit(() -> runner.run(
                    assistantMessage(),
                    toolCall("waiting_tool"),
                    null,
                    ToolRunOptions.withCancellationToken(source.token())
            ));

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            source.cancel();
            release.countDown();
            ToolResultMessage result = future.get(2, TimeUnit.SECONDS);

            assertTrue(result.isError());
            assertTrue(MessageContents.text(result).contains("Tool execution cancelled."));
            assertTrue(callbackRan.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    public void testExpiredTimeoutReturnsTimedOutResult() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RequiredTextTool());
        ToolRunner runner = new ToolRunner(registry);

        ToolResultMessage result = runner.run(
                assistantMessage(),
                toolCall("required_text"),
                null,
                ToolRunOptions.withTimeout(java.time.Duration.ZERO)
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("Tool execution timed out."));
    }

    private static AssistantMessage assistantMessage() {
        return AssistantMessage.builder()
                .provider("fake")
                .model("test-model")
                .contents(List.of(TextContent.builder().text("Use tool.").build()))
                .build();
    }

    private static ToolCallContent toolCall(String toolName) {
        return ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName(toolName)
                .argumentsJson("{\"text\":\"ping\"}")
                .build();
    }

    private static final class WaitingTool implements ToolDefinition {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicBoolean callbackRan;

        private WaitingTool(CountDownLatch entered, CountDownLatch release, AtomicBoolean callbackRan) {
            this.entered = entered;
            this.release = release;
            this.callbackRan = callbackRan;
        }

        @Override
        public String name() {
            return "waiting_tool";
        }

        @Override
        public String label() {
            return "waiting_tool";
        }

        @Override
        public String description() {
            return "Waits until released.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of("text", Map.of("type", "string")),
                    "required", List.of("text")
            );
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            try (AutoCloseable ignored = context.cancellationToken().onCancel(() -> callbackRan.set(true))) {
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
                context.throwIfCancellationRequested();
                return ToolExecutionResult.text("done");
            } catch (ToolCancelledException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class RequiredTextTool implements ToolDefinition {
        @Override
        public String name() {
            return "required_text";
        }

        @Override
        public String label() {
            return "required_text";
        }

        @Override
        public String description() {
            return "Requires text.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of("text", Map.of("type", "string")),
                    "required", List.of("text")
            );
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.text(String.valueOf(context.getArguments().get("text")));
        }
    }
}
