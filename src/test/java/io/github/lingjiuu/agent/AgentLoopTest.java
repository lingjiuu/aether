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
import io.github.lingjiuu.model.ConversationHistory;
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

    public void testRunUsesStepStateMachineWithoutChangingEventOrdering() throws Exception {
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

        ConversationHistory history = new ConversationHistory();
        history.append(UserMessage.builder()
                .contents(List.of(TextContent.builder().text("What time is it?").build()))
                .build());

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new EchoTool());
        config.getTools().addAll(toolRegistry.toAgentTools());
        List<String> callOrder = new ArrayList<>();

        FakeProvider provider = new FakeProvider(
                List.of(
                        responseWithToolCall(),
                        finalResponse()
                ),
                List.of(
                        List.of(AssistantStreamEvent.builder()
                                .type(AssistantStreamEvent.Type.TEXT_DELTA)
                                .delta("Let me check that for you.")
                                .build()),
                        List.of(AssistantStreamEvent.builder()
                                .type(AssistantStreamEvent.Type.TEXT_DELTA)
                                .delta("Done.")
                                .build())
                ),
                callOrder
        );
        AssistantSampler assistantSampler = new AssistantSampler(
                new StubModelRegistry(),
                new ProviderRegistry().register(provider)
        );

        AgentLoop agentLoop = new AgentLoop(
                config,
                history,
                assistantSampler,
                new RecordingContextTransformer(callOrder),
                new RecordingLlmMessageConverter(callOrder),
                new AssistantStreamEventMapper(),
                toolRegistry
        );

        List<AgentEvent.Type> eventTypes = new ArrayList<>();
        List<String> historySizesByEvent = new ArrayList<>();
        agentLoop.run(event -> {
            eventTypes.add(event.getType());
            historySizesByEvent.add(event.getType() + ":" + history.size());
        });

        assertEquals(List.of(
                AgentEvent.Type.RUN_START,
                AgentEvent.Type.TURN_START,
                AgentEvent.Type.ASSISTANT_TEXT_DELTA,
                AgentEvent.Type.ASSISTANT_MESSAGE,
                AgentEvent.Type.TOOL_CALL,
                AgentEvent.Type.TOOL_EXECUTION_START,
                AgentEvent.Type.TOOL_EXECUTION_END,
                AgentEvent.Type.TOOL_RESULT,
                AgentEvent.Type.TURN_START,
                AgentEvent.Type.ASSISTANT_TEXT_DELTA,
                AgentEvent.Type.ASSISTANT_MESSAGE,
                AgentEvent.Type.FINAL_ANSWER,
                AgentEvent.Type.RUN_END
        ), eventTypes);

        assertEquals(List.of(
                "RUN_START:1",
                "TURN_START:1",
                "ASSISTANT_TEXT_DELTA:1",
                "ASSISTANT_MESSAGE:2",
                "TOOL_CALL:2",
                "TOOL_EXECUTION_START:2",
                "TOOL_EXECUTION_END:2",
                "TOOL_RESULT:3",
                "TURN_START:3",
                "ASSISTANT_TEXT_DELTA:3",
                "ASSISTANT_MESSAGE:4",
                "FINAL_ANSWER:4",
                "RUN_END:4"
        ), historySizesByEvent);

        assertEquals(4, history.size());
        assertEquals(2, provider.requestsSeen().size());
        assertEquals(1, provider.requestsSeen().getFirst().getMessages().size());
        assertEquals(3, provider.requestsSeen().get(1).getMessages().size());
        assertEquals("Echo: ping", MessageContents.text(provider.requestsSeen().get(1).getMessages().get(2)));
        assertEquals("Done.", MessageContents.text(history.snapshot().get(3)));
        assertEquals(List.of(
                "transformContext:1",
                "convertToLlm:1",
                "provider.stream:1",
                "transformContext:3",
                "convertToLlm:3",
                "provider.stream:3"
        ), callOrder);
    }

    public void testStreamDeltaEventsDoNotEnterDurableHistory() throws Exception {
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

        ConversationHistory history = new ConversationHistory();
        history.append(UserMessage.builder()
                .contents(List.of(TextContent.builder().text("Say hello").build()))
                .build());

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

        AgentLoop agentLoop = new AgentLoop(config, history, assistantSampler, new ToolRegistry());

        List<Integer> historySizesDuringDeltas = new ArrayList<>();
        agentLoop.run(event -> {
            if (event.getType() == AgentEvent.Type.ASSISTANT_TEXT_DELTA) {
                historySizesDuringDeltas.add(history.size());
            }
        });

        assertEquals(List.of(1, 1), historySizesDuringDeltas);
        assertEquals(2, history.size());
        assertEquals("Done.", MessageContents.text(history.lastMessage()));
    }

    private static AssistantMessage responseWithToolCall() {
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

    private static AssistantMessage finalResponse() {
        return AssistantMessage.builder()
                .model("test-model")
                .provider("fake")
                .stopReason(AssistantMessage.StopReason.STOP)
                .contents(List.of(
                        TextContent.builder().text("Done.").build()
                ))
                .build();
    }

    private static final class FakeProvider implements Provider {
        private final List<AssistantMessage> responses;
        private final List<List<AssistantStreamEvent>> eventBatches;
        private final List<String> callOrder;
        private final List<AssistantRequest> requestsSeen = new ArrayList<>();
        private int invocationCount;

        private FakeProvider(List<AssistantMessage> responses, List<List<AssistantStreamEvent>> eventBatches, List<String> callOrder) {
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

        private List<AssistantRequest> requestsSeen() {
            return requestsSeen;
        }
    }

    private static final class RecordingContextTransformer implements ContextTransformer {
        private final List<String> callOrder;

        private RecordingContextTransformer(List<String> callOrder) {
            this.callOrder = callOrder;
        }

        @Override
        public List<Message> transformContext(List<Message> messages) {
            callOrder.add("transformContext:" + messages.size());
            return List.copyOf(messages);
        }
    }

    private static final class RecordingLlmMessageConverter implements LlmMessageConverter {
        private final List<String> callOrder;

        private RecordingLlmMessageConverter(List<String> callOrder) {
            this.callOrder = callOrder;
        }

        @Override
        public List<Message> convertToLlm(List<Message> messages) {
            callOrder.add("convertToLlm:" + messages.size());
            return List.copyOf(messages);
        }
    }

    private static final class StubModelRegistry extends ModelRegistry {
        private StubModelRegistry() throws IOException {
            super(AuthStorage.create(Files.createTempDirectory("aether-auth-test").resolve("auth.json")));
        }

        @Override
        public ResolvedRequestAuth getApiKeyAndHeaders(AiModel model) {
            return ResolvedRequestAuth.ok("test-key", Map.of());
        }
    }

    private static final class StubAssistantStream extends AssistantStream {
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

    private static final class EchoTool implements ToolDefinition {
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
