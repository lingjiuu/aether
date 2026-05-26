package io.github.lingjiuu.session;

import io.github.lingjiuu.transcript.TranscriptStore;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
