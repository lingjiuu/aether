package io.github.lingjiuu.session;

import io.github.lingjiuu.transcript.TranscriptStore;
import io.github.lingjiuu.tool.Tool;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SessionFactoryTest extends TestCase {

    public void testCreateDefaultLoadsAgentsMdInstructionsForExplicitCwdAndTools() throws Exception {
        Path root = Files.createTempDirectory("aether-session-factory");
        Path cwd = root.resolve("workspace");
        Path agentDir = root.resolve("agent");
        Path configPath = root.resolve("config.toml");
        Files.createDirectories(cwd);
        Files.createDirectories(agentDir);
        writeConfig(configPath);
        Files.writeString(cwd.resolve("AGENTS.md"), "workspace instructions", StandardCharsets.UTF_8);

        SessionFactory factory = SessionFactory.createDefault(
                null,
                null,
                configPath,
                agentDir,
                new TranscriptStore(root.resolve("transcripts"))
        );
        try (Session session = factory.openSession(SessionOptions.cwd(cwd))) {
            assertEquals(cwd.toAbsolutePath().normalize(), session.config().cwd());
            assertTrue(session.config().instructionSources().stream()
                    .anyMatch(path -> path.equals(cwd.resolve("AGENTS.md").toAbsolutePath().normalize())));
            assertTrue(session.config().userInstructions().contains("workspace instructions"));
        }
    }

    public void testExplicitStartupModelSurvivesCwdOnlySessionOptions() throws Exception {
        Path root = Files.createTempDirectory("aether-session-explicit-model");
        Path cwd = root.resolve("workspace");
        Path agentDir = root.resolve("agent");
        Path configPath = root.resolve("config.toml");
        Files.createDirectories(cwd);
        Files.createDirectories(agentDir);
        writeConfig(configPath);

        SessionFactory factory = SessionFactory.createDefault(
                "other",
                "other-model",
                configPath,
                agentDir,
                new TranscriptStore(root.resolve("transcripts"))
        );

        try (Session session = factory.openSession(SessionOptions.cwd(cwd))) {
            assertEquals("other", session.config().endpoint().providerId());
            assertEquals("other-model", session.config().model().getId());
        }
    }

    public void testRetryOptionsLoadFromProviderAndModelOverride() throws Exception {
        Path root = Files.createTempDirectory("aether-session-retry-config");
        Path cwd = root.resolve("workspace");
        Path agentDir = root.resolve("agent");
        Path configPath = root.resolve("config.toml");
        Files.createDirectories(cwd);
        Files.createDirectories(agentDir);
        Files.writeString(configPath, """
                default_provider = "fake"
                default_model = "fake-model"

                [model_providers.fake]
                name = "Fake"
                api = "fake"
                base_url = "http://localhost"
                api_key = "test-key"
                request_max_retries = 7
                stream_max_retries = 8
                retry_initial_delay_ms = 300
                retry_max_delay_ms = 9000
                retry_jitter_ratio = 0.25

                [[model_providers.fake.models]]
                id = "fake-model"
                api = "fake"
                base_url = "http://localhost"
                context_window = 100000
                stream_max_retries = 2
                retry_initial_delay_ms = 50
                input = ["text"]
                """, StandardCharsets.UTF_8);

        SessionFactory factory = SessionFactory.createDefault(
                null,
                null,
                configPath,
                agentDir,
                new TranscriptStore(root.resolve("transcripts"))
        );

        try (Session session = factory.openSession(SessionOptions.cwd(cwd))) {
            var retry = session.config().endpoint().retryOptions();
            assertEquals(7, retry.requestMaxRetries());
            assertEquals(2, retry.streamMaxRetries());
            assertEquals(50L, retry.initialDelayMillis());
            assertEquals(9000L, retry.maxDelayMillis());
            assertEquals(0.25d, retry.jitterRatio(), 0.0001d);
        }
    }

    public void testResumeUsesCwdRecordedInTranscriptMetadata() throws Exception {
        Path root = Files.createTempDirectory("aether-session-resume");
        Path cwd = root.resolve("workspace");
        Path agentDir = root.resolve("agent");
        Path configPath = root.resolve("config.toml");
        Files.createDirectories(cwd);
        Files.createDirectories(agentDir);
        writeConfig(configPath);
        SessionFactory factory = SessionFactory.createDefault(
                null,
                null,
                configPath,
                agentDir,
                new TranscriptStore(root.resolve("transcripts"))
        );

        String sessionId;
        try (Session session = factory.openSession(SessionOptions.cwd(cwd))) {
            sessionId = session.sessionId();
        }

        try (Session resumed = factory.resumeSession(sessionId)) {
            assertEquals(cwd.toAbsolutePath().normalize(), resumed.config().cwd());
        }
    }

    public void testResumeUsesTranscriptModelWhenCurrentDefaultChanges() throws Exception {
        Path root = Files.createTempDirectory("aether-session-resume-model");
        Path cwd = root.resolve("workspace");
        Path agentDir = root.resolve("agent");
        Path configPath = root.resolve("config.toml");
        TranscriptStore transcriptStore = new TranscriptStore(root.resolve("transcripts"));
        Files.createDirectories(cwd);
        Files.createDirectories(agentDir);
        writeConfig(configPath, "fake", "fake-model", true);
        SessionFactory factory = SessionFactory.createDefault(
                null,
                null,
                configPath,
                agentDir,
                transcriptStore
        );

        String sessionId;
        try (Session session = factory.openSession(SessionOptions.cwd(cwd))) {
            sessionId = session.sessionId();
        }

        writeConfig(configPath, "other", "other-model", true);
        SessionFactory resumedFactory = SessionFactory.createDefault(
                null,
                null,
                configPath,
                agentDir,
                transcriptStore
        );

        try (Session resumed = resumedFactory.resumeSession(sessionId)) {
            assertEquals("fake", resumed.config().endpoint().providerId());
            assertEquals("fake-model", resumed.config().model().getId());
        }
    }

    public void testResumeRejectsWhenTranscriptProviderIsNotConfigured() throws Exception {
        Path root = Files.createTempDirectory("aether-session-resume-missing-provider");
        Path cwd = root.resolve("workspace");
        Path agentDir = root.resolve("agent");
        Path configPath = root.resolve("config.toml");
        TranscriptStore transcriptStore = new TranscriptStore(root.resolve("transcripts"));
        Files.createDirectories(cwd);
        Files.createDirectories(agentDir);
        writeConfig(configPath, "fake", "fake-model", true);
        SessionFactory factory = SessionFactory.createDefault(
                null,
                null,
                configPath,
                agentDir,
                transcriptStore
        );

        String sessionId;
        try (Session session = factory.openSession(SessionOptions.cwd(cwd))) {
            sessionId = session.sessionId();
        }

        writeConfig(configPath, "other", "other-model", false);
        SessionFactory resumedFactory = SessionFactory.createDefault(
                null,
                null,
                configPath,
                agentDir,
                transcriptStore
        );

        try {
            resumedFactory.resumeSession(sessionId);
            fail("resume should reject when transcript provider is no longer configured");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Cannot resume session " + sessionId));
            assertTrue(e.getMessage().contains("Model provider \"fake\" is not configured."));
        }
    }

    public void testResumeUsesLatestPersistedModelChange() throws Exception {
        Path root = Files.createTempDirectory("aether-session-resume-model-change");
        Path cwd = root.resolve("workspace");
        Path agentDir = root.resolve("agent");
        Path configPath = root.resolve("config.toml");
        TranscriptStore transcriptStore = new TranscriptStore(root.resolve("transcripts"));
        Files.createDirectories(cwd);
        Files.createDirectories(agentDir);
        writeConfig(configPath, "fake", "fake-model", true);
        SessionFactory factory = SessionFactory.createDefault(
                null,
                null,
                configPath,
                agentDir,
                transcriptStore
        );

        String sessionId;
        try (Session session = factory.openSession(SessionOptions.cwd(cwd))) {
            sessionId = session.sessionId();
            session.setActiveModelSelection(factory.selectModel("other", "other-model", "HIGH"));
            session.waitForIdle();
        }

        try (Session resumed = factory.resumeSession(sessionId)) {
            assertEquals("other", resumed.activeModelSelection().endpoint().providerId());
            assertEquals("other-model", resumed.activeModelSelection().model().getId());
            assertEquals("HIGH", resumed.activeModelSelection().reasoning().getReasoningEffort().name());
        }
    }

    public void testDefaultToolsAreSelectedForHostShellEnvironment() throws Exception {
        Path cwd = Files.createTempDirectory("aether-session-tools");

        List<String> unixTools = toolNames(SessionFactory.buildDefaultTools(cwd, false, false, true));
        assertFalse(unixTools.contains("ls"));
        assertTrue(unixTools.contains("Bash"));
        assertFalse(unixTools.contains("PowerShell"));

        List<String> windowsPowerShellOnly = toolNames(SessionFactory.buildDefaultTools(cwd, true, false, true));
        assertTrue(windowsPowerShellOnly.contains("PowerShell"));
        assertFalse(windowsPowerShellOnly.contains("Bash"));

        List<String> windowsBothShells = toolNames(SessionFactory.buildDefaultTools(cwd, true, true, true));
        assertTrue(windowsBothShells.contains("PowerShell"));
        assertTrue(windowsBothShells.contains("Bash"));

        List<String> windowsNoShells = toolNames(SessionFactory.buildDefaultTools(cwd, true, false, false));
        assertFalse(windowsNoShells.contains("PowerShell"));
        assertFalse(windowsNoShells.contains("Bash"));
    }

    private List<String> toolNames(List<Tool> tools) {
        return tools.stream().map(Tool::name).toList();
    }

    private void writeConfig(Path configPath) throws Exception {
        writeConfig(configPath, "fake", "fake-model", true);
    }

    private void writeConfig(Path configPath, String defaultProvider, String defaultModel, boolean includeFakeProvider) throws Exception {
        Files.writeString(configPath, """
                default_provider = "%s"
                default_model = "%s"

                %s

                [model_providers.other]
                name = "Other"
                api = "fake"
                base_url = "http://localhost"
                api_key = "test-key"

                [[model_providers.other.models]]
                id = "other-model"
                api = "fake"
                base_url = "http://localhost"
                context_window = 100000
                input = ["text"]
                """.formatted(defaultProvider, defaultModel, includeFakeProvider ? fakeProviderConfig() : ""), StandardCharsets.UTF_8);
    }

    private String fakeProviderConfig() {
        return """
                [model_providers.fake]
                name = "Fake"
                api = "fake"
                base_url = "http://localhost"
                api_key = "test-key"

                [[model_providers.fake.models]]
                id = "fake-model"
                api = "fake"
                base_url = "http://localhost"
                context_window = 100000
                input = ["text"]
                """;
    }
}
