package io.github.lingjiuu.session;

import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.RequestAuth;
import junit.framework.TestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentSessionIntegrationTest extends TestCase {

    public void testFactoryBootsSessionAndRunsPromptEndToEnd() throws Exception {
        AuthStorage authStorage = AuthStorage.create(Files.createTempDirectory("aether-auth-test").resolve("auth.json"));
        StubModelRegistry modelRegistry = new StubModelRegistry(authStorage);
        SingleResponseProvider provider = new SingleResponseProvider();
        LlmClient llmClient = new LlmClient(modelRegistry, new ProviderRegistry().register(provider));

        AgentSessionFactory factory = new AgentSessionFactory(AgentSessionConfig.builder()
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
                .build());

        AgentSession session = factory.openSession();
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
        assertEquals(1, provider.invocationCount);
        assertEquals("You are a helpful assistant", provider.lastRequest.getSystemPrompt());
        assertEquals(1, provider.lastRequest.getMessages().size());
        assertEquals("Hello", MessageContents.text(provider.lastRequest.getMessages().getFirst()));
        assertEquals(2, session.messages().size());
        assertEquals("Hello", MessageContents.text(session.messages().getFirst()));
        assertEquals("Done.", MessageContents.text(session.messages().get(1)));
    }

    private static final class SingleResponseProvider implements Provider {
        private int invocationCount;
        private LlmRequest lastRequest;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public AssistantStream stream(LlmRequest request) {
            invocationCount++;
            lastRequest = request;
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
        private StubModelRegistry(AuthStorage authStorage) throws IOException {
            super(authStorage);
        }

        @Override
        public RequestAuth getRequestAuth(LlmModel model) {
            return RequestAuth.ok("test-key", Map.of());
        }
    }
}
