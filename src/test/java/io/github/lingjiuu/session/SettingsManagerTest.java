package io.github.lingjiuu.session;

import io.github.lingjiuu.llm.ReasoningOptions;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;

public class SettingsManagerTest extends TestCase {

    public void testMissingSettingsAreEmpty() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-settings-test");
        SettingsManager settings = SettingsManager.create(tempDir.resolve("settings.json"));

        assertNull(settings.getDefaultProvider());
        assertNull(settings.getDefaultModel());
        assertNull(settings.getError());
    }

    public void testSavesAndReloadsDefaultModel() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-settings-test");
        Path settingsPath = tempDir.resolve("settings.json");
        SettingsManager settings = SettingsManager.create(settingsPath);

        settings.setDefaultModelAndProvider("local-openai", "demo-model");
        SettingsManager reloaded = SettingsManager.create(settingsPath);

        assertEquals("local-openai", reloaded.getDefaultProvider());
        assertEquals("demo-model", reloaded.getDefaultModel());
        assertNull(reloaded.getError());
    }

    public void testMalformedSettingsExposeErrorAndStayEmpty() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-settings-test");
        Path settingsPath = tempDir.resolve("settings.json");
        Files.writeString(settingsPath, "{");

        SettingsManager settings = SettingsManager.create(settingsPath);

        assertNull(settings.getDefaultProvider());
        assertNull(settings.getDefaultModel());
        assertNotNull(settings.getError());
    }

    public void testReadsDefaultThinkingLevel() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-settings-test");
        Path settingsPath = tempDir.resolve("settings.json");
        Files.writeString(settingsPath, """
                {
                  "defaultProvider": "openai",
                  "defaultModel": "gpt-5.4-mini",
                  "defaultThinkingLevel": "medium"
                }
                """);

        SettingsManager settings = SettingsManager.create(settingsPath);

        assertEquals(ReasoningOptions.ReasoningEffort.MEDIUM, settings.getDefaultThinkingLevel());
    }
}
