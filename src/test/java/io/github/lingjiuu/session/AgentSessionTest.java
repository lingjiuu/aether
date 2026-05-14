package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.turn.AgentLoop;
import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.RequestAuth;
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

public class AgentSessionTest extends TestCase {

    public void testPromptKeepsSessionApiBehaviorAndSnapshot() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry();
        StubModelRegistry modelRegistry = new StubModelRegistry();
        AuthStorage authStorage = AuthStorage.create(Files.createTempDirectory("aether-auth-test").resolve("auth.json"));

        LlmClient llmClient = new LlmClient(
                modelRegistry,
                new ProviderRegistry().register(new SingleResponseProvider())
        );
        AgentSessionConfig config = AgentSessionConfig.builder()
                .authStorage(authStorage)
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
        AgentSessionServices services = new AgentSessionServices(
                config,
                modelRegistry,
                toolRegistry,
                llmClient
        );
        AgentLoop agentLoop = new AgentLoop(services);
        AgentSession session = new AgentSession(services, agentLoop);

        List<AgentSessionEvent.Type> eventTypes = new ArrayList<>();
        session.subscribe(event -> eventTypes.add(event.getType()));

        session.prompt("Hello");

        assertEquals(List.of(
                AgentSessionEvent.Type.USER_MESSAGE,
                AgentSessionEvent.Type.RUN_START,
                AgentSessionEvent.Type.TURN_START,
                AgentSessionEvent.Type.ASSISTANT_TEXT_DELTA,
                AgentSessionEvent.Type.ASSISTANT_MESSAGE,
                AgentSessionEvent.Type.FINAL_ANSWER,
                AgentSessionEvent.Type.RUN_END
        ), eventTypes);
        assertEquals(2, session.messages().size());
        assertEquals("Hello", ((TextContent) session.messages().getFirst().messageContents().getFirst()).getText());
        assertEquals("Done.", ((TextContent) session.messages().get(1).messageContents().getFirst()).getText());
        assertFalse(session.canContinue());
        assertEquals(config, session.config());
        assertEquals(2, session.snapshot().getMessages().size());
        assertEquals(config, session.snapshot().getConfig());
    }

    public void testSetActiveToolsControlsNextRequestToolsAndPrompt() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new PromptTool("first", "First tool"));
        toolRegistry.register(new PromptTool("second", "Second tool"));
        StubModelRegistry modelRegistry = new StubModelRegistry();
        CapturingProvider provider = new CapturingProvider();
        AuthStorage authStorage = AuthStorage.create(Files.createTempDirectory("aether-auth-test").resolve("auth.json"));

        LlmClient llmClient = new LlmClient(
                modelRegistry,
                new ProviderRegistry().register(provider)
        );
        AgentSessionConfig config = AgentSessionConfig.builder()
                .authStorage(authStorage)
                .modelRegistry(modelRegistry)
                .llmClient(llmClient)
                .systemPrompt("Base")
                .model(LlmModel.builder()
                        .id("test-model")
                        .name("Test Model")
                        .api("fake")
                        .provider("fake")
                        .baseUrl("https://example.test/v1")
                        .build())
                .activeToolNames(List.of("first"))
                .build();
        AgentSessionServices services = new AgentSessionServices(config, modelRegistry, toolRegistry, llmClient);
        AgentSession session = new AgentSession(services, new AgentLoop(services));

        assertEquals(List.of("first"), session.activeToolNames());
        session.setActiveToolsByName(List.of("second"));
        session.prompt("Hello");

        assertEquals(List.of("second"), session.activeToolNames());
        assertEquals(1, provider.lastRequest.getTools().size());
        assertEquals("second", provider.lastRequest.getTools().getFirst().name());
        assertTrue(provider.lastRequest.getSystemPrompt().contains("- second: Second tool"));
        assertFalse(provider.lastRequest.getSystemPrompt().contains("- first: First tool"));
    }

    private static final class SingleResponseProvider implements Provider {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public AssistantStream stream(LlmRequest request) {
            return new AssistantStream() {
                @Override
                public AssistantMessage consume(java.util.function.Consumer<AssistantStreamEvent> consumer) {
                    consumer.accept(AssistantStreamEvent.builder()
                            .type(AssistantStreamEvent.Type.TEXT_DELTA)
                            .delta("Done.")
                            .build());
                    return AssistantMessage.builder()
                            .provider("fake")
                            .model("test-model")
                            .stopReason(AssistantMessage.StopReason.STOP)
                            .contents(List.of(TextContent.builder().text("Done.").build()))
                            .build();
                }

                @Override
                public AssistantMessage result() {
                    return null;
                }
            };
        }
    }

    private static final class CapturingProvider implements Provider {
        private LlmRequest lastRequest;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public AssistantStream stream(LlmRequest request) {
            lastRequest = request;
            return new SingleResponseProvider().stream(request);
        }
    }

    private static final class PromptTool implements ToolDefinition {
        private final String name;
        private final String promptSnippet;

        private PromptTool(String name, String promptSnippet) {
            this.name = name;
            this.promptSnippet = promptSnippet;
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
            return name;
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of("type", "object");
        }

        @Override
        public String promptSnippet() {
            return promptSnippet;
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.text(name);
        }
    }

    private static final class StubModelRegistry extends ModelRegistry {
        private StubModelRegistry() throws IOException {
            super(AuthStorage.create(Files.createTempDirectory("aether-auth-test").resolve("auth.json")));
        }

        @Override
        public RequestAuth getRequestAuth(LlmModel model) {
            return RequestAuth.ok("test-key", Map.of());
        }
    }
}
