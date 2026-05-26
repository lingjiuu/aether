package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.ProviderSession;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionBuilder;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ToolCancellationClosureTest extends TestCase {

    public void testCancelRecordsAbortedToolResultForInFlightToolCall() throws Exception {
        CountDownLatch toolCallEmitted = new CountDownLatch(1);
        CountDownLatch streamMayFinish = new CountDownLatch(1);
        CountDownLatch toolStarted = new CountDownLatch(1);

        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-cancel")
                .toolName("slow")
                .argumentsJson("{}")
                .build();
        Session session = new SessionBuilder()
                .config(sessionConfig(
                        new FakeProvider(toolCall, toolCallEmitted, streamMayFinish),
                        new SlowTool(toolStarted)
                ))
                .build();

        session.submitAsync(io.github.lingjiuu.input.TurnInput.ofText("run slow tool"));
        assertTrue(toolCallEmitted.await(5, TimeUnit.SECONDS));
        assertTrue(toolStarted.await(5, TimeUnit.SECONDS));

        assertTrue(session.cancelRunningTask());
        streamMayFinish.countDown();
        assertTrue(session.waitForIdle(java.time.Duration.ofSeconds(5)));

        ToolResultMessage abortedResult = session.messages()
                .stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(message -> "call-cancel".equals(message.getToolCallId()))
                .findFirst()
                .orElse(null);

        assertNotNull(abortedResult);
        assertTrue(abortedResult.isError());
        assertTrue(MessageContents.text(abortedResult).matches("aborted by user after \\d+\\.\\ds"));
    }

    public void testBashCancelUsesCodexShellAbortFormat() throws Exception {
        CountDownLatch toolCallEmitted = new CountDownLatch(1);
        CountDownLatch streamMayFinish = new CountDownLatch(1);
        CountDownLatch toolStarted = new CountDownLatch(1);

        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-bash-cancel")
                .toolName("bash")
                .argumentsJson("{}")
                .build();
        Session session = new SessionBuilder()
                .config(sessionConfig(
                        new FakeProvider(toolCall, toolCallEmitted, streamMayFinish),
                        new SlowTool(toolStarted, "bash", ToolRiskLevel.READ_ONLY)
                ))
                .build();

        session.submitAsync(io.github.lingjiuu.input.TurnInput.ofText("run bash tool"));
        assertTrue(toolCallEmitted.await(5, TimeUnit.SECONDS));
        assertTrue(toolStarted.await(5, TimeUnit.SECONDS));

        assertTrue(session.cancelRunningTask());
        streamMayFinish.countDown();
        assertTrue(session.waitForIdle(java.time.Duration.ofSeconds(5)));

        String resultText = session.messages()
                .stream()
                .filter(ToolResultMessage.class::isInstance)
                .map(ToolResultMessage.class::cast)
                .filter(message -> "call-bash-cancel".equals(message.getToolCallId()))
                .map(MessageContents::text)
                .findFirst()
                .orElse("");

        assertTrue(resultText.matches("Wall time: \\d+\\.\\d seconds\\naborted by user"));
    }

    private SessionConfig sessionConfig(Provider provider, ToolDefinition tool) {
        return new SessionConfig(
                new LlmClient(new ProviderRegistry().register(provider)),
                "You are a test agent.",
                "",
                "",
                List.of(),
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
                List.of(tool.name())
        );
    }

    private record FakeProvider(
            ToolCallContent toolCall,
            CountDownLatch toolCallEmitted,
            CountDownLatch streamMayFinish
    ) implements Provider {

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public ProviderSession openSession(LlmModel model, RequestAuth auth) {
            return (request, cancellationToken) -> new FakeStream(toolCall, toolCallEmitted, streamMayFinish);
        }
    }

    private static final class FakeStream extends AssistantStream {
        private final ToolCallContent toolCall;
        private final CountDownLatch toolCallEmitted;
        private final CountDownLatch streamMayFinish;
        private AssistantMessage result;

        private FakeStream(
                ToolCallContent toolCall,
                CountDownLatch toolCallEmitted,
                CountDownLatch streamMayFinish
        ) {
            this.toolCall = toolCall;
            this.toolCallEmitted = toolCallEmitted;
            this.streamMayFinish = streamMayFinish;
        }

        @Override
        public AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TOOLCALL_END)
                    .toolCall(toolCall)
                    .partial(toolCallMessage())
                    .build());
            toolCallEmitted.countDown();
            await(streamMayFinish);
            result = AssistantMessage.builder()
                    .stopReason(AssistantMessage.StopReason.ABORTED)
                    .contents(List.of(TextContent.builder().text("").build()))
                    .build();
            return result;
        }

        @Override
        public AssistantMessage result() {
            return result;
        }

        private AssistantMessage toolCallMessage() {
            return AssistantMessage.builder()
                    .contents(List.of(toolCall))
                    .stopReason(AssistantMessage.StopReason.TOOLUSE)
                    .build();
        }
    }

    private record SlowTool(CountDownLatch toolStarted, String name, ToolRiskLevel riskLevel) implements ToolDefinition {
        private SlowTool(CountDownLatch toolStarted) {
            this(toolStarted, "slow", ToolRiskLevel.READ_ONLY);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String label() {
            return "Slow";
        }

        @Override
        public String description() {
            return "A cancellable slow test tool.";
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
            return riskLevel;
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context) {
            toolStarted.countDown();
            while (!context.cancellationToken().isCancellationRequested()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            context.throwIfCancellationRequested();
            return ToolExecutionResult.text("done");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
