package io.github.lingjiuu.session;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;

public class AgentSessionFactoryTest extends TestCase {

    public void testCreateDefaultUsesSettingsModel() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-factory-test");
        Path authPath = tempDir.resolve("auth.json");
        Path modelsPath = tempDir.resolve("models.json");
        Path settingsPath = tempDir.resolve("settings.json");
        Files.writeString(modelsPath, """
                {
                  "providers": {
                    "local-openai": {
                      "baseUrl": "http://localhost:1234/v1",
                      "api": "openai",
                      "apiKey": "local-key",
                      "models": [{ "id": "demo-model" }]
                    }
                  }
                }
                """);
        SettingsManager.create(settingsPath).setDefaultModelAndProvider("local-openai", "demo-model");

        AgentSessionFactory factory = AgentSessionFactory.createDefault(null, null, authPath, modelsPath, settingsPath);

        assertEquals("local-openai", factory.configuration().getModel().getProvider());
        assertEquals("demo-model", factory.configuration().getModel().getId());
        assertNotNull(factory.configuration().getSettingsManager());
    }

    public void testCreateDefaultUsesSettingsThinkingLevel() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-factory-test");
        Path settingsPath = tempDir.resolve("settings.json");
        Files.writeString(settingsPath, """
                {
                  "defaultProvider": "openai",
                  "defaultModel": "gpt-4.1",
                  "defaultThinkingLevel": "medium"
                }
                """);

        AgentSessionFactory factory = AgentSessionFactory.createDefault(
                null,
                null,
                tempDir.resolve("auth.json"),
                tempDir.resolve("models.json"),
                settingsPath
        );

        assertEquals("openai", factory.configuration().getModel().getProvider());
        assertEquals(io.github.lingjiuu.llm.ReasoningOptions.ReasoningEffort.MEDIUM,
                factory.configuration().getReasoning().getReasoningEffort());
    }

    public void testExplicitModelBeatsSettings() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-factory-test");
        Path settingsPath = tempDir.resolve("settings.json");
        SettingsManager.create(settingsPath).setDefaultModelAndProvider("openai", "gpt-4.1");

        AgentSessionFactory factory = AgentSessionFactory.createDefault(
                "bailian",
                "qwen3.5-plus-2026-02-15",
                tempDir.resolve("auth.json"),
                tempDir.resolve("models.json"),
                settingsPath
        );

        assertEquals("bailian", factory.configuration().getModel().getProvider());
        assertEquals("qwen3.5-plus-2026-02-15", factory.configuration().getModel().getId());
    }

    public void testCreateDefaultFailsWithoutSettings() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-factory-test");

        try {
            AgentSessionFactory.createDefault(
                    null,
                    null,
                    tempDir.resolve("auth.json"),
                    tempDir.resolve("models.json"),
                    tempDir.resolve("settings.json")
            );
            fail("Expected no default model error");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("No default model configured"));
        }
    }

}
