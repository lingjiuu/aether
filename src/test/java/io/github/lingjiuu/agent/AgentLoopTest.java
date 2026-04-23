package io.github.lingjiuu.agent;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import io.github.lingjiuu.ai.AiModel;
import io.github.lingjiuu.ai.AssistantRequest;
import io.github.lingjiuu.ai.AssistantSampler;
import io.github.lingjiuu.ai.ModelRegistry;
import io.github.lingjiuu.ai.ProviderRegistry;
import io.github.lingjiuu.ai.ResolvedRequestAuth;
import io.github.lingjiuu.auth.AuthStorage;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.AgentConfig;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.stream.AssistantStream;
import io.github.lingjiuu.stream.AssistantStreamEvent;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import junit.framework.TestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentLoopTest extends TestCase {

    public void testRunTurnReturnsToolResultsWithoutMutatingRuntimeState() throws Exception {
        AgentConfig config = AgentConfig.builder()
                .systemPrompt("You are a helpful assistant")
                .model(AiModel.builder()
                        .id("test-model")
                        .name("Test Model")
                        .api("fake")
                        .provider("fake")
                        .baseUrl("https://example.test/v1")
                        .build())
                .build();

        AgentRuntimeState runtimeState = new AgentRuntimeState(List.of(
                UserMessage.builder()
                        .contents(List.of(TextContent.builder().text("What time is it?").build()))
                        .build()
        ));

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        config.getTools().addAll(toolRegistry.toAgentTools());
        List<String> callOrder = new ArrayList<>();

        FakeProvider provider = new FakeProvider(
                List.of(responseWithToolCall()),
                List.of(List.of(AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.TEXT_DELTA)
                        .delta("Let me check that for you.")
                        .build())),
                callOrder
        );
        AssistantSampler assistantSampler = new AssistantSampler(
                new StubModelRegistry(),
                new ProviderRegistry().register(provider)
        );

        AgentLoop agentLoop = new AgentLoop(
                config,
                assistantSampler,
                new RecordingContextTransformer(callOrder),
                new RecordingLlmMessageConverter(callOrder),
                new AssistantStreamEventMapper(),
                toolRegistry
        );

        TurnResult turnResult = agentLoop.runTurn(runtimeState);

        assertEquals(1, runtimeState.size());
        assertEquals(List.of(
                AgentEvent.Type.TURN_START,
                AgentEvent.Type.ASSISTANT_TEXT_DELTA,
                AgentEvent.Type.ASSISTANT_MESSAGE,
                AgentEvent.Type.TOOL_CALL,
                AgentEvent.Type.TOOL_EXECUTION_START,
                AgentEvent.Type.TOOL_EXECUTION_END,
                AgentEvent.Type.TOOL_RESULT
        ), turnResult.events().stream().map(AgentEvent::getType).toList());
        assertEquals(2, turnResult.appendedMessages().size());
        assertEquals(TurnResult.Transition.NEXT_TURN, turnResult.transition());
        assertNull(turnResult.terminationReason());
        assertEquals(1, provider.requestsSeen().size());
        assertEquals(1, provider.requestsSeen().getFirst().getMessages().size());
        assertEquals("Let me check that for you.", MessageContents.text(turnResult.appendedMessages().getFirst()));
        assertEquals("Echo: ping", MessageContents.text(turnResult.appendedMessages().get(1)));
        assertEquals(List.of(
                "transformContext:1",
                "convertToLlm:1",
                "provider.stream:1"
        ), callOrder);
    }

    public void testRunTurnReturnsFinalAnswerWithoutMutatingRuntimeState() throws Exception {
        AgentConfig config = AgentConfig.builder()
                .systemPrompt("You are a helpful assistant")
                .model(AiModel.builder()
                        .id("test-model")
                        .name("Test Model")
                        .api("fake")
                        .provider("fake")
                        .baseUrl("https://example.test/v1")
                        .build())
                .build();

        AgentRuntimeState runtimeState = new AgentRuntimeState(List.of(
                UserMessage.builder()
                .contents(List.of(TextContent.builder().text("What time is it?").build()))
                        .build()
        ));

        FakeProvider provider = new FakeProvider(
                List.of(finalResponse()),
                List.of(List.of(
                        AssistantStreamEvent.builder().type(AssistantStreamEvent.Type.TEXT_DELTA).delta("Do").build(),
                        AssistantStreamEvent.builder().type(AssistantStreamEvent.Type.TEXT_DELTA).delta("ne.").build()
                )),
                new ArrayList<>()
        );
        AssistantSampler assistantSampler = new AssistantSampler(
                new StubModelRegistry(),
                new ProviderRegistry().register(provider)
        );

        AgentLoop agentLoop = new AgentLoop(config, assistantSampler, new ToolRegistry());

        TurnResult turnResult = agentLoop.runTurn(runtimeState);

        assertEquals(1, runtimeState.size());
        assertEquals(List.of(
                AgentEvent.Type.TURN_START,
                AgentEvent.Type.ASSISTANT_TEXT_DELTA,
                AgentEvent.Type.ASSISTANT_TEXT_DELTA,
                AgentEvent.Type.ASSISTANT_MESSAGE,
                AgentEvent.Type.FINAL_ANSWER
        ), turnResult.events().stream().map(AgentEvent::getType).toList());
        assertEquals(1, turnResult.appendedMessages().size());
        assertEquals(TurnResult.Transition.FINISH, turnResult.transition());
        assertEquals(AgentRuntimeState.TerminationReason.COMPLETED, turnResult.terminationReason());
        assertEquals("Done.", MessageContents.text(turnResult.appendedMessages().getFirst()));
    }

    static AssistantMessage responseWithToolCall() {
        return AssistantMessage.builder()
                .model("test-model")
                .provider("fake")
                .stopReason(AssistantMessage.StopReason.TOOLUSE)
                .contents(List.of(
                        TextContent.builder().text("Let me check that for you.").build(),
                        ToolCallContent.builder()
                                .toolCallId("call-1")
                                .toolName("echo_tool")
                                .argumentsJson("{\"text\":\"ping\"}")
                                .build()
                ))
                .build();
    }

    static AssistantMessage finalResponse() {
        return AssistantMessage.builder()
                .model("test-model")
                .provider("fake")
                .stopReason(AssistantMessage.StopReason.STOP)
                .contents(List.of(
                        TextContent.builder().text("Done.").build()
                ))
                .build();
    }

    static final class FakeProvider implements Provider {
        private final List<AssistantMessage> responses;
        private final List<List<AssistantStreamEvent>> eventBatches;
        private final List<String> callOrder;
        private final List<AssistantRequest> requestsSeen = new ArrayList<>();
        private int invocationCount;

        FakeProvider(List<AssistantMessage> responses, List<List<AssistantStreamEvent>> eventBatches, List<String> callOrder) {
            this.responses = responses;
            this.eventBatches = eventBatches;
            this.callOrder = callOrder;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public AssistantStream stream(AssistantRequest request) {
            callOrder.add("provider.stream:" + request.getMessages().size());
            requestsSeen.add(request);
            int index = invocationCount++;
            return new StubAssistantStream(responses.get(index), eventBatches.get(index));
        }

        List<AssistantRequest> requestsSeen() {
            return requestsSeen;
        }
    }

    static final class RecordingContextTransformer implements ContextTransformer {
        private final List<String> callOrder;

        RecordingContextTransformer(List<String> callOrder) {
            this.callOrder = callOrder;
        }

        @Override
        public List<Message> transformContext(List<Message> messages) {
            callOrder.add("transformContext:" + messages.size());
            return List.copyOf(messages);
        }
    }

    static final class RecordingLlmMessageConverter implements LlmMessageConverter {
        private final List<String> callOrder;

        RecordingLlmMessageConverter(List<String> callOrder) {
            this.callOrder = callOrder;
        }

        @Override
        public List<Message> convertToLlm(List<Message> messages) {
            callOrder.add("convertToLlm:" + messages.size());
            return List.copyOf(messages);
        }
    }

    static final class StubModelRegistry extends ModelRegistry {
        StubModelRegistry() throws IOException {
            super(AuthStorage.create(Files.createTempDirectory("aether-auth-test").resolve("auth.json")));
        }

        @Override
        public ResolvedRequestAuth getApiKeyAndHeaders(AiModel model) {
            return ResolvedRequestAuth.ok("test-key", Map.of());
        }
    }

    static final class StubAssistantStream extends AssistantStream {
        private final AssistantMessage result;
        private final List<AssistantStreamEvent> events;

        private StubAssistantStream(AssistantMessage result, List<AssistantStreamEvent> events) {
            this.result = result;
            this.events = events;
        }

        @Override
        public AssistantMessage consume(java.util.function.Consumer<AssistantStreamEvent> consumer) {
            for (AssistantStreamEvent event : events) {
                consumer.accept(event);
            }
            return result;
        }

        @Override
        public AssistantMessage result() {
            return result;
        }
    }

    static final class EchoTool implements ToolDefinition {
        @Override
        public String name() {
            return "echo_tool";
        }

        @Override
        public String label() {
            return "Echo Tool";
        }

        @Override
        public String description() {
            return "Echoes the provided text.";
        }

        @Override
        public FunctionTool schema() {
            return FunctionTool.builder()
                    .name(name())
                    .description(description())
                    .strict(true)
                    .parameters(FunctionTool.Parameters.builder()
                            .putAdditionalProperty("type", JsonValue.from("object"))
                            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                                    "text", Map.of(
                                            "type", "string",
                                            "description", "The text to echo back"
                                    )
                            )))
                            .putAdditionalProperty("required", JsonValue.from(List.of("text")))
                            .build())
                    .build();
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.text("Echo: " + context.getArguments().get("text"));
        }
    }
}
