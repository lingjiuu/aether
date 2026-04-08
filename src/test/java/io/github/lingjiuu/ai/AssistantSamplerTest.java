package io.github.lingjiuu.ai;

import io.github.lingjiuu.auth.AuthStorage;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.model.AgentConfig;
import io.github.lingjiuu.model.Reasoning;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderOptions;
import io.github.lingjiuu.stream.AssistantStream;
import junit.framework.TestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public class AssistantSamplerTest extends TestCase {

    public void testSamplerBuildsProviderNeutralRequestAndMergesAuth() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        AssistantSampler sampler = new AssistantSampler(
                new StubModelRegistry(),
                new ProviderRegistry().register(provider)
        );

        AssistantRequest request = AssistantRequest.builder()
                .config(AgentConfig.builder()
                        .systemPrompt("You are helpful")
                        .model(AiModel.builder()
                                .id("fake-model")
                                .name("Fake Model")
                                .api("fake")
                                .provider("fake-provider")
                                .baseUrl("https://example.test")
                                .build())
                        .reasoning(Reasoning.builder()
                                .reasoningEffort(Reasoning.ReasoningEffort.HIGH)
                                .build())
                        .build())
                .messages(List.of(UserMessage.builder()
                        .contents(List.of(TextContent.builder().text("Hello").build()))
                        .build()))
                .options(ProviderOptions.builder()
                        .temperature(0.2)
                        .build())
                .build();

        try (AssistantStream ignored = sampler.stream(request)) {
            assertNotNull(ignored);
        }

        assertNotNull(provider.capturedRequest);
        assertEquals("You are helpful", provider.capturedRequest.getConfig().getSystemPrompt());
        assertEquals("Hello", ((TextContent) provider.capturedRequest.getMessages().getFirst().messageContents().getFirst()).getText());
        assertEquals("test-key", provider.capturedRequest.getOptions().getApiKey());
        assertEquals("trace-1", provider.capturedRequest.getOptions().getHeaders().get("X-Test-Trace"));
        assertEquals(Double.valueOf(0.2), provider.capturedRequest.getOptions().getTemperature());
        assertEquals(Reasoning.ReasoningEffort.HIGH, provider.capturedRequest.getOptions().getReasoning().getReasoningEffort());
    }

    private static final class CapturingProvider implements Provider {
        private AssistantRequest capturedRequest;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public AssistantStream stream(AssistantRequest request) {
            this.capturedRequest = request;
            return new AssistantStream() {
                @Override
                public AssistantMessage consume(java.util.function.Consumer<io.github.lingjiuu.stream.AssistantStreamEvent> consumer) {
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
        public ResolvedRequestAuth getApiKeyAndHeaders(AiModel model) {
            return ResolvedRequestAuth.ok("test-key", Map.of("X-Test-Trace", "trace-1"));
        }
    }
}
