package io.github.lingjiuu.session;

import io.github.lingjiuu.infra.auth.ApiKeyCredential;
import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.llm.LlmModel;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;

public class ModelResolverTest extends TestCase {

    public void testResolvesProviderModelReference() throws Exception {
        ModelRegistry registry = registryWithModels(null);

        LlmModel model = new ModelResolver().resolveCliModel(registry, null, "openai/gpt-4.1");

        assertEquals("openai", model.getProvider());
        assertEquals("gpt-4.1", model.getId());
    }

    public void testResolvesUnambiguousBareModelId() throws Exception {
        ModelRegistry registry = registryWithModels("""
                {
                  "providers": {
                    "local-openai": {
                      "baseUrl": "http://localhost:1234/v1",
                      "api": "openai",
                      "apiKey": "local-key",
                      "models": [{ "id": "unique-model" }]
                    }
                  }
                }
                """);

        LlmModel model = new ModelResolver().resolveCliModel(registry, null, "unique-model");

        assertEquals("local-openai", model.getProvider());
        assertEquals("unique-model", model.getId());
    }

    public void testExplicitProviderCanCreateFallbackModelId() throws Exception {
        ModelRegistry registry = registryWithModels(null);

        LlmModel model = new ModelResolver().resolveCliModel(registry, "openai", "future-model");

        assertEquals("openai", model.getProvider());
        assertEquals("future-model", model.getId());
        assertEquals("openai", model.getApi());
        assertEquals("https://api.openai.com/v1", model.getBaseUrl());
    }

    public void testAmbiguousBareModelIdRequiresProvider() throws Exception {
        ModelRegistry registry = registryWithModels(null);

        try {
            new ModelResolver().resolveCliModel(registry, null, "qwen3.5-plus-2026-02-15");
            fail("Expected ambiguous model error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("ambiguous"));
        }
    }

    public void testSettingsDefaultIsUsed() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-resolver-test");
        Path settingsPath = tempDir.resolve("settings.json");
        SettingsManager settings = SettingsManager.create(settingsPath);
        settings.setDefaultModelAndProvider("openai", "gpt-4.1");
        ModelRegistry registry = new ModelRegistry(AuthStorage.create(tempDir.resolve("auth.json")), tempDir.resolve("models.json"));

        LlmModel model = new ModelResolver().findInitialModel(registry, settings, null, null);

        assertEquals("openai", model.getProvider());
        assertEquals("gpt-4.1", model.getId());
    }

    public void testMissingExplicitProviderReportsClearError() throws Exception {
        ModelRegistry registry = registryWithModels(null);

        try {
            new ModelResolver().findInitialModel(
                    registry,
                    SettingsManager.create(Files.createTempDirectory("aether-resolver-test").resolve("settings.json")),
                    "missing",
                    "model"
            );
            fail("Expected missing model error");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("No model configured for missing/model"));
        }
    }

    public void testNoSettingsReportsClearErrorEvenWhenAuthExists() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-resolver-test");
        AuthStorage authStorage = AuthStorage.create(tempDir.resolve("auth.json"));
        authStorage.set("openai", ApiKeyCredential.builder().key("openai-key").build());
        ModelRegistry registry = new ModelRegistry(authStorage, tempDir.resolve("models.json"));

        try {
            new ModelResolver().findInitialModel(
                    registry,
                    SettingsManager.create(tempDir.resolve("settings.json")),
                    null,
                    null
            );
            fail("Expected no default model error");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("No default model configured"));
        }
    }

    private ModelRegistry registryWithModels(String json) throws Exception {
        Path tempDir = Files.createTempDirectory("aether-resolver-test");
        Path modelsPath = tempDir.resolve("models.json");
        if (json != null) {
            Files.writeString(modelsPath, json);
        }
        return new ModelRegistry(AuthStorage.create(tempDir.resolve("auth.json")), modelsPath);
    }

}
