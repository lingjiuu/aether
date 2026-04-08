package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.AgentLoop;
import io.github.lingjiuu.ai.AiModel;
import io.github.lingjiuu.ai.AssistantRequest;
import io.github.lingjiuu.ai.AssistantSampler;
import io.github.lingjiuu.ai.ModelRegistry;
import io.github.lingjiuu.ai.ProviderRegistry;
import io.github.lingjiuu.ai.ResolvedRequestAuth;
import io.github.lingjiuu.auth.AuthStorage;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.model.AgentConfig;
import io.github.lingjiuu.model.ConversationHistory;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.stream.AssistantStream;
import io.github.lingjiuu.stream.AssistantStreamEvent;
import io.github.lingjiuu.tool.ToolRegistry;
import junit.framework.TestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentSessionTest extends TestCase {

    public void testPromptKeepsSessionApiBehaviorAndSnapshot() throws Exception {
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
        ToolRegistry toolRegistry = new ToolRegistry();
        StubModelRegistry modelRegistry = new StubModelRegistry();
        AuthStorage authStorage = AuthStorage.create(Files.createTempDirectory("aether-auth-test").resolve("auth.json"));

        AssistantSampler assistantSampler = new AssistantSampler(
                modelRegistry,
                new ProviderRegistry().register(new SingleResponseProvider())
        );
        AgentLoop agentLoop = new AgentLoop(config, history, assistantSampler, toolRegistry);
        AgentSession session = new AgentSession(
                authStorage,
                modelRegistry,
                toolRegistry,
                config,
                history,
                agentLoop
        );

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

    private static final class SingleResponseProvider implements Provider {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public AssistantStream stream(AssistantRequest request) {
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

    private static final class StubModelRegistry extends ModelRegistry {
        private StubModelRegistry() throws IOException {
            super(AuthStorage.create(Files.createTempDirectory("aether-auth-test").resolve("auth.json")));
        }

        @Override
        public ResolvedRequestAuth getApiKeyAndHeaders(AiModel model) {
            return ResolvedRequestAuth.ok("test-key", Map.of());
        }
    }
}
