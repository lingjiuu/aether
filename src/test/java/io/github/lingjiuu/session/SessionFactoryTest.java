package io.github.lingjiuu.session;

import io.github.lingjiuu.resource.ContextFile;
import io.github.lingjiuu.transcript.TranscriptStore;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SessionFactoryTest extends TestCase {

    public void testCreateDefaultUsesExplicitCwdForPromptResourcesAndTools() throws Exception {
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
            assertTrue(session.config().promptResources().getContextFiles().stream()
                    .map(ContextFile::getPath)
                    .anyMatch(path -> path.equals(cwd.resolve("AGENTS.md").toAbsolutePath().normalize())));
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

    private void writeConfig(Path configPath) throws Exception {
        Files.writeString(configPath, """
                default_provider = "fake"
                default_model = "fake-model"

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
                """, StandardCharsets.UTF_8);
    }
}
