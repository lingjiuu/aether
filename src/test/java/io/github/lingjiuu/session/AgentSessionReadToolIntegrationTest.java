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
import io.github.lingjiuu.message.content.ImageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.tools.ReadTool;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import io.github.lingjiuu.transcript.TranscriptRecord;
import io.github.lingjiuu.transcript.TranscriptStore;
import junit.framework.TestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentSessionReadToolIntegrationTest extends TestCase {

    public void testReadToolResultReachesNextRequestAndTranscript() throws Exception {
        Path root = Files.createTempDirectory("aether-read-session-root");
        Files.writeString(root.resolve("notes.txt"), "alpha\nbeta\n");
        TranscriptStore transcriptStore = new TranscriptStore(Files.createTempDirectory("aether-read-transcripts"));
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadTool(WorkspaceAccessPolicy.rootedAt(root)));

        StubModelRegistry modelRegistry = new StubModelRegistry();
        ReadThenFinalProvider provider = new ReadThenFinalProvider();
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

        session.prompt("Read notes.txt");

        assertEquals(2, provider.requestsSeen().size());
        LlmRequest secondRequest = provider.requestsSeen().get(1);
        ToolResultMessage requestToolResult = findToolResult(secondRequest.getMessages());
        assertNotNull(requestToolResult);
        assertEquals("read", requestToolResult.getToolName());
        assertFalse(requestToolResult.isError());
        assertTrue(MessageContents.text(requestToolResult).contains("alpha\nbeta"));
        assertTrue(secondRequest.getSystemPrompt().contains("- read: Read file contents"));
        assertTrue(secondRequest.getSystemPrompt().contains("Use read to examine files"));

        List<TranscriptRecord> records = transcriptStore.read(session.sessionId());
        assertEquals(4, records.size());
        ToolResultMessage transcriptToolResult = findToolResult(records.stream()
                .map(TranscriptRecord::getMessage)
                .toList());
        assertNotNull(transcriptToolResult);
        assertTrue(MessageContents.text(transcriptToolResult).contains("beta"));
        assertTrue(events.contains(AgentSessionEvent.Type.TOOL_RESULT));
    }

    public void testImageReadToolResultReachesNextRequestAndTranscript() throws Exception {
        Path root = Files.createTempDirectory("aether-read-session-root");
        Files.write(root.resolve("pixel.png"), tinyPngBytes());
        TranscriptStore transcriptStore = new TranscriptStore(Files.createTempDirectory("aether-read-transcripts"));
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadTool(WorkspaceAccessPolicy.rootedAt(root)));

        StubModelRegistry modelRegistry = new StubModelRegistry();
        ReadThenFinalProvider provider = new ReadThenFinalProvider("pixel.png");
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

        session.prompt("Read pixel.png");

        assertEquals(2, provider.requestsSeen().size());
        ToolResultMessage requestToolResult = findToolResult(provider.requestsSeen().get(1).getMessages());
        assertNotNull(requestToolResult);
        assertFalse(requestToolResult.isError());
        assertEquals("read", requestToolResult.getToolName());
        assertTrue(MessageContents.text(requestToolResult).contains("Read image file [image/png]"));
        assertEquals(2, requestToolResult.getContents().size());
        assertTrue(requestToolResult.getContents().get(1) instanceof ImageContent);
        assertEquals("image/png", ((ImageContent) requestToolResult.getContents().get(1)).getMimeType());

        ToolResultMessage transcriptToolResult = findToolResult(transcriptStore.read(session.sessionId()).stream()
                .map(TranscriptRecord::getMessage)
                .toList());
        assertNotNull(transcriptToolResult);
        assertTrue(transcriptToolResult.getContents().get(1) instanceof ImageContent);
        assertEquals(((ImageContent) requestToolResult.getContents().get(1)).getData(),
                ((ImageContent) transcriptToolResult.getContents().get(1)).getData());
    }

    private ToolResultMessage findToolResult(List<Message> messages) {
        for (Message message : messages) {
            if (message instanceof ToolResultMessage toolResultMessage) {
                return toolResultMessage;
            }
        }
        return null;
    }

    private static final class ReadThenFinalProvider implements Provider {
        private final List<LlmRequest> requestsSeen = new ArrayList<>();
        private final String path;

        private ReadThenFinalProvider() {
            this("notes.txt");
        }

        private ReadThenFinalProvider(String path) {
            this.path = path;
        }

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
                                TextContent.builder().text("Reading the file.").build(),
                                ToolCallContent.builder()
                                        .toolCallId("call-1")
                                        .toolName("read")
                                        .argumentsJson("{\"path\":\"" + path + "\"}")
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

    private byte[] tinyPngBytes() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x0d,
                0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00,
                (byte) 0x90, 0x77, 0x53, (byte) 0xde,
                0x00, 0x00, 0x00, 0x0a,
                0x49, 0x44, 0x41, 0x54,
                0x08, (byte) 0xd7, 0x63, 0x60, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01,
                (byte) 0xe2, 0x21, (byte) 0xbc, 0x33,
                0x00, 0x00, 0x00, 0x00,
                0x49, 0x45, 0x4e, 0x44,
                (byte) 0xae, 0x42, 0x60, (byte) 0x82
        };
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
