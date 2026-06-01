package io.github.lingjiuu.transport.stdio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.TestModelSelections;
import io.github.lingjiuu.model.client.AssistantStream;
import io.github.lingjiuu.model.client.AssistantStreamEvent;
import io.github.lingjiuu.model.client.ModelClient;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.wire.WireAdapter;
import io.github.lingjiuu.wire.WireAdapterRegistry;
import io.github.lingjiuu.wire.WireSession;
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
import java.util.function.Consumer;

public class StdioAetherServerTest extends TestCase {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public void testInitializeHistoryAndSkillsUseJsonLines() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-test");
        String input = """
                {"id":"1","method":"initialize"}
                {"id":"2","method":"history/read"}
                {"id":"3","method":"skills/list"}
                {"id":"4","method":"session/current"}
                """;
        String output = runServer(tempDir, input);
        List<String> lines = output.lines().toList();

        assertEquals(4, lines.size());
        JsonNode initialize = objectMapper.readTree(lines.get(0));
        assertEquals("1", initialize.get("id").asText());
        assertEquals(StdioAetherServer.PROTOCOL_VERSION, initialize.get("result").get("protocolVersion").asText());
        assertTrue(initialize.get("result").hasNonNull("sessionId"));
        assertEquals("0.1.1", initialize.get("result").get("session").get("appVersion").asText());
        assertEquals("IDLE", initialize.get("result").get("session").get("status").asText());
        assertTrue(initialize.get("result").get("capabilities").get("sessionState").asBoolean());
        assertTrue(initialize.get("result").get("capabilities").get("permissionSelection").asBoolean());
        assertEquals(0, initialize.get("result").get("history").get("turns").size());

        JsonNode history = objectMapper.readTree(lines.get(1));
        assertEquals("2", history.get("id").asText());
        assertEquals(0, history.get("result").get("turns").size());

        JsonNode skills = objectMapper.readTree(lines.get(2));
        assertEquals("3", skills.get("id").asText());
        assertTrue(skills.get("result").isArray());

        JsonNode currentSession = objectMapper.readTree(lines.get(3));
        assertEquals("4", currentSession.get("id").asText());
        assertTrue(currentSession.get("result").hasNonNull("sessionId"));
        assertEquals("IDLE", currentSession.get("result").get("status").asText());
        assertEquals(0, currentSession.get("result").get("messageCount").asInt());
        assertEquals("fake", currentSession.get("result").get("summary").get("modelProvider").asText());
        assertEquals("fake-model", currentSession.get("result").get("summary").get("modelId").asText());
        assertTrue(currentSession.get("result").has("reasoningEffort"));
        assertTrue(currentSession.get("result").get("reasoningEffort").isNull());
        assertEquals("DEFAULT", currentSession.get("result").get("permissionMode").asText());
        assertEquals(100000L, currentSession.get("result").get("tokenUsage").get("modelContextWindow").asLong());
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

    public void testModelListResponseIncludesCurrentSelection() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-model-list-test");
        String output = runServer(tempDir, """
                {"id":"1","method":"model/list"}
                """);

        JsonNode response = objectMapper.readTree(output);
        assertEquals("1", response.get("id").asText());
        JsonNode result = response.get("result");
        assertEquals("fake", result.get("current").get("providerId").asText());
        assertEquals("fake-model", result.get("current").get("modelId").asText());
        assertEquals(1, result.get("models").size());
        assertTrue(result.get("models").get(0).get("current").asBoolean());
        assertTrue(result.get("reasoningEfforts").isArray());
    }

    public void testModelSetRequiresModelId() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-model-set-test");
        String output = runServer(tempDir, """
                {"id":"1","method":"model/set","params":{}}
                """);

        JsonNode response = objectMapper.readTree(output);
        assertEquals("1", response.get("id").asText());
        assertEquals(-32602, response.get("error").get("code").asInt());
        assertTrue(response.get("error").get("message").asText().contains("modelId"));
    }

    public void testSessionCurrentIncludesReasoningEffortAfterModelSet() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-reasoning-test");
        String output = runServer(tempDir, """
                {"id":"1","method":"model/set","params":{"providerId":"fake","modelId":"fake-model","reasoningEffort":"HIGH"}}
                {"id":"2","method":"session/current"}
                """);

        JsonNode currentSession = responseById(output, "2");
        assertEquals("2", currentSession.get("id").asText());
        assertEquals("HIGH", currentSession.get("result").get("reasoningEffort").asText());
    }

    public void testPermissionListAndSetUpdateCurrentSession() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-permission-test");
        String output = runServer(tempDir, """
                {"id":"1","method":"permission/list"}
                {"id":"2","method":"permission/set","params":{"permissionMode":"FULL_ACCESS"}}
                {"id":"3","method":"session/current"}
                """);

        JsonNode list = responseById(output, "1");
        assertEquals("DEFAULT", list.get("result").get("current").get("id").asText());
        assertEquals(2, list.get("result").get("modes").size());

        JsonNode set = responseById(output, "2");
        assertTrue(set.get("result").get("accepted").asBoolean());
        assertTrue(set.get("result").get("message").asText().contains("Full Access"));

        JsonNode currentSession = responseById(output, "3");
        assertEquals("FULL_ACCESS", currentSession.get("result").get("permissionMode").asText());
    }

    public void testPermissionSetRequiresMode() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-permission-set-test");
        String output = runServer(tempDir, """
                {"id":"1","method":"permission/set","params":{}}
                """);

        JsonNode response = objectMapper.readTree(output);
        assertEquals("1", response.get("id").asText());
        assertEquals(-32602, response.get("error").get("code").asInt());
        assertTrue(response.get("error").get("message").asText().contains("permissionMode"));
    }

    public void testTurnSubmitRequiresStructuredItems() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-submit-items-test");
        String output = runServer(tempDir, """
                {"id":"1","method":"turn/submit","params":{"items":[{"type":"text","text":"hello"}]}}
                """);

        JsonNode response = responseById(output, "1");
        assertEquals("1", response.get("id").asText());
        assertTrue(response.get("result").get("accepted").asBoolean());
    }

    public void testTurnSubmitRejectsLegacyTextParam() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-submit-legacy-test");
        String output = runServer(tempDir, """
                {"id":"1","method":"turn/submit","params":{"text":"hello"}}
                """);

        JsonNode response = responseById(output, "1");
        assertEquals("1", response.get("id").asText());
        assertEquals(-32602, response.get("error").get("code").asInt());
        assertTrue(response.get("error").get("message").asText().contains("params.items"));
    }

    public void testSkillsListCanForceReloadFromDisk() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-skills-reload-test");
        SessionFactory sessionFactory = new SessionFactory(sessionConfig(tempDir));

        String firstOutput = runServer(sessionFactory, """
                {"id":"1","method":"skills/list"}
                """);
        JsonNode firstResponse = objectMapper.readTree(firstOutput);
        assertEquals(0, firstResponse.get("result").size());

        Path skillPath = tempDir.resolve(".aether/skills/demo/SKILL.md");
        writeSkill(skillPath, "demo", "Demo skill");

        String cachedOutput = runServer(sessionFactory, """
                {"id":"2","method":"skills/list","params":{"forceReload":false}}
                """);
        JsonNode cachedResponse = objectMapper.readTree(cachedOutput);
        assertEquals(0, cachedResponse.get("result").size());

        String reloadedOutput = runServer(sessionFactory, """
                {"id":"3","method":"skills/list","params":{"forceReload":true}}
                """);
        JsonNode reloadedResponse = objectMapper.readTree(reloadedOutput);
        assertEquals(1, reloadedResponse.get("result").size());
        assertEquals("demo", reloadedResponse.get("result").get(0).get("name").asText());
    }

    public void testSessionNewRequiresExplicitCwd() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-stdio-new-session-test");
        String output = runServer(tempDir, """
                {"id":"1","method":"session/new"}
                """);

        JsonNode response = objectMapper.readTree(output);
        assertEquals("1", response.get("id").asText());
        assertEquals(-32602, response.get("error").get("code").asInt());
        assertTrue(response.get("error").get("message").asText().contains("params.cwd"));
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
                new ModelClient(new WireAdapterRegistry().register(new NoopProvider())),
                "You are a test agent.",
                "",
                "",
                List.of(),
                cwd.toAbsolutePath().normalize(),
                TestModelSelections.fakeSelection(),
                new TranscriptStore(cwd.resolve("transcripts")),
                List.of(),
                List.of()
        );
    }

    private void writeSkill(Path path, String name, String description) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                ---
                name: %s
                description: %s
                ---

                # Skill
                """.formatted(name, description));
    }

    private JsonNode responseById(String output, String id) throws Exception {
        for (String line : output.lines().toList()) {
            JsonNode response = objectMapper.readTree(line);
            if (id.equals(response.path("id").asText(null))) {
                return response;
            }
        }
        throw new AssertionError("missing response id=" + id + " in output:\n" + output);
    }

    private static final class NoopProvider implements WireAdapter {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WireSession openSession(ModelSelection selection) {
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
