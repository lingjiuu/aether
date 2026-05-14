package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.turn.AgentLoop;
import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.builtin.GrepTool;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;
import io.github.lingjiuu.transcript.TranscriptRecord;
import io.github.lingjiuu.transcript.TranscriptStore;
import junit.framework.TestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentSessionGrepToolIntegrationTest extends TestCase {

    public void testGrepToolResultReachesNextRequestAndTranscript() throws Exception {
        Path root = Files.createTempDirectory("aether-grep-session-root");
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\n");
        TranscriptStore transcriptStore = new TranscriptStore(Files.createTempDirectory("aether-grep-transcripts"));
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new GrepTool(FileAccessPolicy.rootedAt(root)));

        StubModelRegistry modelRegistry = new StubModelRegistry();
        GrepThenFinalProvider provider = new GrepThenFinalProvider();
        LlmClient llmClient = new LlmClient(modelRegistry, new ProviderRegistry().register(provider));
        AgentSessionConfig config = AgentSessionConfig.builder()
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
                .transcriptStore(transcriptStore)
                .build();
        AgentSessionServices services = new AgentSessionServices(config, modelRegistry, toolRegistry, llmClient);
        AgentSession session = new AgentSession(services, new AgentLoop(services));

        List<AgentSessionEvent.Type> events = new ArrayList<>();
        session.subscribe(event -> events.add(event.getType()));

        session.prompt("Search notes.txt for beta");

        assertEquals(2, provider.requestsSeen().size());
        LlmRequest secondRequest = provider.requestsSeen().get(1);
        ToolResultMessage requestToolResult = findToolResult(secondRequest.getMessages());
        assertNotNull(requestToolResult);
        assertEquals("grep", requestToolResult.getToolName());
        assertFalse(requestToolResult.isError());
        assertTrue(MessageContents.text(requestToolResult).contains("notes.txt:2: beta"));
        assertTrue(secondRequest.getSystemPrompt().contains("- grep: Search file contents for patterns"));
        assertTrue(secondRequest.getSystemPrompt().contains("Use grep to search file contents"));

        List<TranscriptRecord> records = transcriptStore.read(session.sessionId());
        assertEquals(4, records.size());
        ToolResultMessage transcriptToolResult = findToolResult(records.stream()
                .map(TranscriptRecord::getMessage)
                .toList());
        assertNotNull(transcriptToolResult);
        assertTrue(MessageContents.text(transcriptToolResult).contains("beta"));
        assertTrue(events.contains(AgentSessionEvent.Type.TOOL_RESULT));
    }

    private ToolResultMessage findToolResult(List<Message> messages) {
        for (Message message : messages) {
            if (message instanceof ToolResultMessage toolResultMessage) {
                return toolResultMessage;
            }
        }
        return null;
    }

    private static final class GrepThenFinalProvider implements Provider {
        private final List<LlmRequest> requestsSeen = new ArrayList<>();

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public AssistantStream stream(LlmRequest request) {
            requestsSeen.add(request);
            if (requestsSeen.size() == 1) {
                return new FixedAssistantStream(AssistantMessage.builder()
                        .provider("fake")
                        .model("test-model")
                        .stopReason(AssistantMessage.StopReason.TOOLUSE)
                        .contents(List.of(
                                TextContent.builder().text("Searching the file.").build(),
                                ToolCallContent.builder()
                                        .toolCallId("call-1")
                                        .toolName("grep")
                                        .argumentsJson("{\"pattern\":\"beta\",\"path\":\"notes.txt\",\"literal\":true}")
                                        .build()
                        ))
                        .build());
            }
            return new FixedAssistantStream(AssistantMessage.builder()
                    .provider("fake")
                    .model("test-model")
                    .stopReason(AssistantMessage.StopReason.STOP)
                    .contents(List.of(TextContent.builder().text("Done.").build()))
                    .build());
        }

        private List<LlmRequest> requestsSeen() {
            return requestsSeen;
        }
    }

    private static final class FixedAssistantStream extends AssistantStream {
        private final AssistantMessage result;

        private FixedAssistantStream(AssistantMessage result) {
            this.result = result;
        }

        @Override
        public AssistantMessage consume(java.util.function.Consumer<AssistantStreamEvent> consumer) {
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_DELTA)
                    .delta(MessageContents.text(result))
                    .build());
            return result;
        }

        @Override
        public AssistantMessage result() {
            return result;
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
