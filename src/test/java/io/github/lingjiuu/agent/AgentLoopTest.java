package io.github.lingjiuu.agent;

import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.session.AgentSessionConfig;
import io.github.lingjiuu.session.AgentSessionServices;
import io.github.lingjiuu.session.ModelRegistry;
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
        AgentRuntimeState runtimeState = new AgentRuntimeState(List.of(
                UserMessage.builder()
                        .contents(List.of(TextContent.builder().text("What time is it?").build()))
                        .build()
        ));

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        List<String> callOrder = new ArrayList<>();
        StubModelRegistry modelRegistry = new StubModelRegistry();

        FakeProvider provider = new FakeProvider(
                List.of(responseWithToolCall()),
                List.of(List.of(AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.TEXT_DELTA)
                        .delta("Let me check that for you.")
                        .build())),
                callOrder
        );
        LlmClient llmClient = new LlmClient(
                modelRegistry,
                new ProviderRegistry().register(provider)
        );
        AgentSessionConfig config = sessionConfig(modelRegistry, llmClient);
        AgentSessionServices services = new AgentSessionServices(
                config,
                modelRegistry,
                toolRegistry,
                llmClient
        );

        AgentLoop agentLoop = new AgentLoop(
                services,
                new RecordingContextTransformer(callOrder),
                new RecordingLlmMessageConverter(callOrder),
                new AssistantStreamEventMapper()
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
        StubModelRegistry modelRegistry = new StubModelRegistry();
        LlmClient llmClient = new LlmClient(
                modelRegistry,
                new ProviderRegistry().register(provider)
        );
        AgentSessionConfig config = sessionConfig(modelRegistry, llmClient);
        AgentSessionServices services = new AgentSessionServices(
                config,
                modelRegistry,
                new ToolRegistry(),
                llmClient
        );

        AgentLoop agentLoop = new AgentLoop(services);

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

    static AgentSessionConfig sessionConfig(ModelRegistry modelRegistry, LlmClient llmClient) {
        return AgentSessionConfig.builder()
                .modelRegistry(modelRegistry)
                .llmClient(llmClient)
                .systemPrompt("You are a helpful assistant")
                .model(LlmModel.builder()
                        .id("test-model")
                        .name("Test Model")
                        .api("fake")
                        .provider("fake")
                        .baseUrl("https://example.test/v1")
                        .build())
                .build();
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
        private final List<LlmRequest> requestsSeen = new ArrayList<>();
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
        public AssistantStream stream(LlmRequest request) {
            callOrder.add("provider.stream:" + request.getMessages().size());
            requestsSeen.add(request);
            int index = invocationCount++;
            return new StubAssistantStream(responses.get(index), eventBatches.get(index));
        }

        List<LlmRequest> requestsSeen() {
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
        public RequestAuth getRequestAuth(LlmModel model) {
            return RequestAuth.ok("test-key", Map.of());
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
            return "Echo the provided text";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "text", Map.of("type", "string")
                    ),
                    "required", List.of("text")
            );
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            String text = String.valueOf(context.getArguments().get("text"));
            return ToolExecutionResult.text("Echo: " + text);
        }
    }
}
