package io.github.lingjiuu.session;

import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import io.github.lingjiuu.transcript.TranscriptRecord;
import io.github.lingjiuu.transcript.TranscriptStore;
import junit.framework.TestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentSessionTranscriptIntegrationTest extends TestCase {

    public void testSessionRecordsToolConversationAndRestoresMessages() throws Exception {
        TranscriptStore transcriptStore = new TranscriptStore(Files.createTempDirectory("aether-session-transcript-test"));
        StubModelRegistry modelRegistry = new StubModelRegistry();
        ToolConversationProvider provider = new ToolConversationProvider();
        LlmClient llmClient = new LlmClient(modelRegistry, new ProviderRegistry().register(provider));
        AgentSessionFactory factory = new AgentSessionFactory(AgentSessionConfig.builder()
                .authStorage(AuthStorage.create(Files.createTempDirectory("aether-auth-test").resolve("auth.json")))
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
                .toolDefinitions(List.of(new EchoTool()))
                .transcriptStore(transcriptStore)
                .build());

        AgentSession session = factory.openSession();

        session.prompt("Echo ping");

        List<TranscriptRecord> records = transcriptStore.read(session.sessionId());
        assertEquals(4, records.size());
        assertEquals(Message.Role.USER, records.get(0).getMessage().role());
        assertEquals(Message.Role.ASSISTANT, records.get(1).getMessage().role());
        assertEquals(Message.Role.TOOLRESULT, records.get(2).getMessage().role());
        assertEquals(Message.Role.ASSISTANT, records.get(3).getMessage().role());
        assertNull(records.get(0).getParentRecordId());
        assertEquals(records.get(0).getId(), records.get(1).getParentRecordId());
        assertEquals(records.get(1).getId(), records.get(2).getParentRecordId());
        assertEquals(records.get(2).getId(), records.get(3).getParentRecordId());
        assertEquals("Echo ping", MessageContents.text(records.get(0).getMessage()));
        assertEquals("Echo: ping", MessageContents.text(records.get(2).getMessage()));
        assertEquals("Done.", MessageContents.text(records.get(3).getMessage()));

        AgentSession restored = factory.resumeSession(session.sessionId());

        assertEquals(session.sessionId(), restored.sessionId());
        assertEquals(4, restored.messages().size());
        assertEquals("Echo ping", MessageContents.text(restored.messages().getFirst()));
        assertEquals("Echo: ping", MessageContents.text(restored.messages().get(2)));
        assertEquals("Done.", MessageContents.text(restored.messages().get(3)));
        assertEquals(2, provider.requestsSeen().size());
        assertEquals(3, provider.requestsSeen().get(1).getMessages().size());
    }

    private static final class ToolConversationProvider implements Provider {
        private final List<LlmRequest> requestsSeen = new ArrayList<>();
        private int invocationCount;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public AssistantStream stream(LlmRequest request) {
            requestsSeen.add(request);
            AssistantMessage response = invocationCount++ == 0
                    ? AssistantMessage.builder()
                    .provider("fake")
                    .model("test-model")
                    .stopReason(AssistantMessage.StopReason.TOOLUSE)
                    .contents(List.of(
                            TextContent.builder().text("I will echo that.").build(),
                            ToolCallContent.builder()
                                    .toolCallId("call-1")
                                    .toolName("echo_tool")
                                    .argumentsJson("{\"text\":\"ping\"}")
                                    .build()
                    ))
                    .build()
                    : AssistantMessage.builder()
                    .provider("fake")
                    .model("test-model")
                    .stopReason(AssistantMessage.StopReason.STOP)
                    .contents(List.of(TextContent.builder().text("Done.").build()))
                    .build();
            return new StaticAssistantStream(response);
        }

        private List<LlmRequest> requestsSeen() {
            return requestsSeen;
        }
    }

    private static final class StaticAssistantStream extends AssistantStream {
        private final AssistantMessage response;

        private StaticAssistantStream(AssistantMessage response) {
            this.response = response;
        }

        @Override
        public AssistantMessage consume(java.util.function.Consumer<AssistantStreamEvent> consumer) {
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_DELTA)
                    .delta(MessageContents.text(response))
                    .build());
            return response;
        }

        @Override
        public AssistantMessage result() {
            return response;
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
            return ToolExecutionResult.text("Echo: " + context.getArguments().get("text"));
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
