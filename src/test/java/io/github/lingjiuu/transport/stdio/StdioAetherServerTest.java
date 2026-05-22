package io.github.lingjiuu.transport.stdio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.provider.ProviderSession;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.resource.PromptResources;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.transcript.TranscriptStore;
import io.github.lingjiuu.ui.UiRuntime;
import junit.framework.TestCase;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class StdioAetherServerTest extends TestCase {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public void testInitializeHistoryAndSkillsUseJsonLines() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-test");
        String input = """
                {"id":"1","method":"initialize"}
                {"id":"2","method":"history/read"}
                {"id":"3","method":"skills/list"}
                """;
        String output = runServer(tempDir, input);
        List<String> lines = output.lines().toList();

        assertEquals(3, lines.size());
        JsonNode initialize = objectMapper.readTree(lines.get(0));
        assertEquals("1", initialize.get("id").asText());
        assertEquals(StdioAetherServer.PROTOCOL_VERSION, initialize.get("result").get("protocolVersion").asText());
        assertTrue(initialize.get("result").hasNonNull("sessionId"));
        assertEquals(0, initialize.get("result").get("history").get("turns").size());

        JsonNode history = objectMapper.readTree(lines.get(1));
        assertEquals("2", history.get("id").asText());
        assertEquals(0, history.get("result").get("turns").size());

        JsonNode skills = objectMapper.readTree(lines.get(2));
        assertEquals("3", skills.get("id").asText());
        assertTrue(skills.get("result").isArray());
    }

    public void testSessionListAndEventPagingResponses() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-sessions-test");
        SessionFactory sessionFactory = new SessionFactory(sessionConfig(tempDir));
        String sessionId;
        try (var session = sessionFactory.openSession()) {
            sessionId = session.sessionId();
            session.events().emit(io.github.lingjiuu.protocol.UiEvent.builder()
                    .type(io.github.lingjiuu.protocol.UiEventType.TURN_STARTED)
                    .sessionId(session.sessionId())
                    .turn(1)
                    .build());
            session.events().emit(io.github.lingjiuu.protocol.UiEvent.builder()
                    .type(io.github.lingjiuu.protocol.UiEventType.TURN_COMPLETED)
                    .sessionId(session.sessionId())
                    .turn(1)
                    .build());
            session.waitForIdle();
        }

        String input = """
                {"id":"1","method":"session/list"}
                {"id":"2","method":"session/resume","params":{"sessionId":"%s"}}
                {"id":"3","method":"events/list","params":{"afterSequence":0,"limit":1}}
                """.formatted(sessionId);
        String output = runServer(sessionFactory, input);
        List<String> lines = output.lines().toList();

        JsonNode sessions = objectMapper.readTree(lines.get(0));
        assertEquals("1", sessions.get("id").asText());
        assertTrue(sessions.get("result").isArray());
        assertFalse(sessions.get("result").isEmpty());

        JsonNode resume = objectMapper.readTree(lines.get(1));
        assertEquals("2", resume.get("id").asText());
        assertTrue(resume.get("result").get("accepted").asBoolean());

        JsonNode events = objectMapper.readTree(lines.get(2));
        assertEquals("3", events.get("id").asText());
        assertEquals(1, events.get("result").get("events").size());
        assertTrue(events.get("result").get("hasMore").asBoolean());
    }

    public void testEventPageRequiresReplayForDifferentSession() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-replay-test");
        String output = runServer(tempDir, """
                {"id":"1","method":"events/list","params":{"sessionId":"other-session","afterSequence":10}}
                """);

        JsonNode page = objectMapper.readTree(output);
        assertEquals("1", page.get("id").asText());
        assertTrue(page.get("result").get("replayRequired").asBoolean());
        assertEquals(0, page.get("result").get("events").size());
    }

    public void testUnknownMethodReturnsJsonRpcError() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-error-test");
        String output = runServer(tempDir, "{\"id\":4,\"method\":\"wat\"}\n");

        JsonNode response = objectMapper.readTree(output);
        assertEquals(4, response.get("id").asInt());
        assertEquals(-32601, response.get("error").get("code").asInt());
        assertTrue(response.get("error").get("message").asText().contains("Unknown method"));
    }

    private String runServer(Path cwd, String input) {
        return runServer(new SessionFactory(sessionConfig(cwd)), input);
    }

    private String runServer(SessionFactory sessionFactory, String input) {
        StringWriter output = new StringWriter();
        try (StdioAetherServer server = new StdioAetherServer(
                new UiRuntime(sessionFactory, null, null),
                new StringReader(input),
                output
        )) {
            server.run();
        }
        return output.toString();
    }

    private SessionConfig sessionConfig(Path cwd) {
        return new SessionConfig(
                new LlmClient(new ProviderRegistry().register(new NoopProvider())),
                "You are a test agent.",
                cwd.toAbsolutePath().normalize(),
                LlmModel.builder()
                        .id("fake-model")
                        .api("fake")
                        .provider("fake")
                        .input(List.of("text"))
                        .contextWindowTokens(100_000L)
                        .build(),
                RequestAuth.ok("test", Map.of()),
                null,
                new TranscriptStore(cwd.resolve("transcripts")),
                List.of(),
                PromptResources.empty(),
                List.of()
        );
    }

    private static final class NoopProvider implements Provider {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public ProviderSession openSession(LlmModel model, RequestAuth auth) {
            return (request, cancellationToken) -> new AssistantStream() {
                @Override
                public io.github.lingjiuu.message.AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
                    return result();
                }

                @Override
                public io.github.lingjiuu.message.AssistantMessage result() {
                    return io.github.lingjiuu.message.AssistantMessage.builder()
                            .stopReason(io.github.lingjiuu.message.AssistantMessage.StopReason.STOP)
                            .build();
                }
            };
        }
    }
}
