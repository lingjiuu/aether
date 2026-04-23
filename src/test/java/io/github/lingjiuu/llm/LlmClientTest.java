package io.github.lingjiuu.llm;

import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.model.AgentConfig;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.session.ModelRegistry;
import junit.framework.TestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public class LlmClientTest extends TestCase {

    public void testClientBuildsProviderNeutralRequestAndMergesAuth() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        LlmClient client = new LlmClient(
                new StubModelRegistry(),
                new ProviderRegistry().register(provider)
        );

        LlmRequest request = LlmRequest.builder()
                .config(AgentConfig.builder()
                        .systemPrompt("You are helpful")
                        .model(LlmModel.builder()
                                .id("fake-model")
                                .name("Fake Model")
                                .api("fake")
                                .provider("fake-provider")
                                .baseUrl("https://example.test")
                                .build())
                        .reasoning(ReasoningOptions.builder()
                                .reasoningEffort(ReasoningOptions.ReasoningEffort.HIGH)
                                .build())
                        .build())
                .messages(List.of(UserMessage.builder()
                        .contents(List.of(TextContent.builder().text("Hello").build()))
                        .build()))
                .callOptions(LlmCallOptions.builder()
                        .temperature(0.2)
                        .build())
                .build();

        try (AssistantStream ignored = client.stream(request)) {
            assertNotNull(ignored);
        }

        assertNotNull(provider.capturedRequest);
        assertEquals("You are helpful", provider.capturedRequest.getConfig().getSystemPrompt());
        assertEquals("Hello", ((TextContent) provider.capturedRequest.getMessages().getFirst().messageContents().getFirst()).getText());
        assertEquals("test-key", provider.capturedRequest.getAuth().getApiKey());
        assertEquals("trace-1", provider.capturedRequest.getAuth().getHeaders().get("X-Test-Trace"));
        assertEquals(Double.valueOf(0.2), provider.capturedRequest.getCallOptions().getTemperature());
        assertEquals(ReasoningOptions.ReasoningEffort.HIGH, provider.capturedRequest.getCallOptions().getReasoning().getReasoningEffort());
    }

    private static final class CapturingProvider implements Provider {
        private LlmRequest capturedRequest;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public AssistantStream stream(LlmRequest request) {
            this.capturedRequest = request;
            return new AssistantStream() {
                @Override
                public AssistantMessage consume(java.util.function.Consumer<AssistantStreamEvent> consumer) {
                    return AssistantMessage.builder()
                            .stopReason(AssistantMessage.StopReason.STOP)
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
        public RequestAuth getRequestAuth(LlmModel model) {
            return RequestAuth.ok("test-key", Map.of("X-Test-Trace", "trace-1"));
        }
    }
}
