package io.github.lingjiuu.session;

import io.github.lingjiuu.infra.auth.ApiKeyCredential;
import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.provider.RequestAuth;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ModelRegistryTest extends TestCase {

    public void testBuiltInModelsExposeAllAndInputMetadata() throws Exception {
        ModelRegistry registry = registryWithModels(null);

        assertNull(registry.getError());
        assertEquals(3, registry.getAll().size());
        assertTrue(registry.find("openai", "gpt-4.1").getInput().contains("image"));
        assertTrue(registry.find("bailian", "qwen3.5-plus-2026-02-15").getInput().contains("image"));
        assertEquals(List.of("text"), registry.find("siliconflow", "qwen3.5-plus-2026-02-15").getInput());
    }

    public void testAvailableModelsRequireConfiguredAuth() throws Exception {
        Path authPath = Files.createTempDirectory("aether-auth-test").resolve("auth.json");
        AuthStorage authStorage = AuthStorage.create(authPath);
        authStorage.set("openai", ApiKeyCredential.builder().key("openai-key").build());

        ModelRegistry registry = new ModelRegistry(authStorage, Files.createTempDirectory("aether-models-test").resolve("missing.json"));

        assertTrue(registry.getAvailable().stream()
                .anyMatch(model -> model.getProvider().equals("openai")));
    }

    public void testBuiltInProviderOverridePreservesBuiltInModel() throws Exception {
        ModelRegistry registry = registryWithModels("""
                {
                  "providers": {
                    "openai": {
                      "baseUrl": "https://proxy.example/v1",
                      "headers": { "X-Provider": "provider-header" }
                    }
                  }
                }
                """);

        LlmModel model = registry.find("openai", "gpt-4.1");

        assertNotNull(model);
        assertEquals("https://proxy.example/v1", model.getBaseUrl());
        assertEquals("provider-header", model.getHeaders().get("X-Provider"));
        assertEquals(3, registry.getAll().size());
    }

    public void testCustomProviderAddsModelAndParsesInput() throws Exception {
        ModelRegistry registry = registryWithModels("""
                {
                  "providers": {
                    "local-openai": {
                      "baseUrl": "http://localhost:1234/v1",
                      "api": "openai",
                      "apiKey": "local-key",
                      "models": [
                        { "id": "demo-model", "name": "Demo Model", "input": ["text", "image"] }
                      ]
                    }
                  }
                }
                """);

        LlmModel model = registry.find("local-openai", "demo-model");

        assertNotNull(model);
        assertEquals("openai", model.getApi());
        assertEquals("http://localhost:1234/v1", model.getBaseUrl());
        assertEquals(List.of("text", "image"), model.getInput());
    }

    public void testCustomModelReplacesSameProviderAndId() throws Exception {
        ModelRegistry registry = registryWithModels("""
                {
                  "providers": {
                    "openai": {
                      "models": [
                        {
                          "id": "gpt-4.1",
                          "name": "Custom GPT",
                          "baseUrl": "https://custom.example/v1",
                          "headers": { "X-Model": "model-header" }
                        }
                      ]
                    }
                  }
                }
                """);

        LlmModel model = registry.find("openai", "gpt-4.1");

        assertEquals(3, registry.getAll().size());
        assertEquals("Custom GPT", model.getName());
        assertEquals("https://custom.example/v1", model.getBaseUrl());
        assertEquals("model-header", model.getHeaders().get("X-Model"));
    }

    public void testMalformedConfigReportsErrorAndKeepsBuiltIns() throws Exception {
        ModelRegistry registry = registryWithModels("{");

        assertNotNull(registry.getError());
        assertTrue(registry.getError().contains("models"));
        assertEquals(3, registry.getAll().size());
        assertNotNull(registry.find("openai", "gpt-4.1"));
    }

    public void testInvalidCustomProviderReportsErrorAndKeepsBuiltIns() throws Exception {
        ModelRegistry registry = registryWithModels("""
                {
                  "providers": {
                    "local-openai": {
                      "baseUrl": "http://localhost:1234/v1",
                      "api": "openai",
                      "models": [{ "id": "demo-model" }]
                    }
                  }
                }
                """);

        assertNotNull(registry.getError());
        assertTrue(registry.getError().contains("apiKey"));
        assertEquals(3, registry.getAll().size());
        assertNull(registry.find("local-openai", "demo-model"));
    }

    public void testRequestAuthMergesHeadersAndAuthHeader() throws Exception {
        ModelRegistry registry = registryWithModels("""
                {
                  "providers": {
                    "local-openai": {
                      "baseUrl": "http://localhost:1234/v1",
                      "api": "openai",
                      "apiKey": "local-key",
                      "authHeader": true,
                      "headers": { "X-Provider": "provider-header" },
                      "models": [
                        {
                          "id": "demo-model",
                          "headers": {
                            "X-Model": "model-header",
                            "X-Provider": "model-wins"
                          }
                        }
                      ]
                    }
                  }
                }
                """);

        RequestAuth auth = registry.getRequestAuth(registry.find("local-openai", "demo-model"));

        assertTrue(auth.isOk());
        assertEquals("local-key", auth.getApiKey());
        assertEquals("Bearer local-key", auth.getHeaders().get("Authorization"));
        assertEquals("model-wins", auth.getHeaders().get("X-Provider"));
        assertEquals("model-header", auth.getHeaders().get("X-Model"));
    }

    public void testAuthStorageBeatsModelConfigApiKey() throws Exception {
        Path tempDir = Files.createTempDirectory("aether-registry-test");
        Path modelsPath = tempDir.resolve("models.json");
        Files.writeString(modelsPath, """
                {
                  "providers": {
                    "local-openai": {
                      "baseUrl": "http://localhost:1234/v1",
                      "api": "openai",
                      "apiKey": "model-config-key",
                      "models": [{ "id": "demo-model" }]
                    }
                  }
                }
                """);
        AuthStorage authStorage = AuthStorage.create(tempDir.resolve("auth.json"));
        authStorage.set("local-openai", ApiKeyCredential.builder().key("stored-key").build());
        ModelRegistry registry = new ModelRegistry(authStorage, modelsPath);

        RequestAuth auth = registry.getRequestAuth(registry.find("local-openai", "demo-model"));

        assertTrue(auth.isOk());
        assertEquals("stored-key", auth.getApiKey());
    }

    private ModelRegistry registryWithModels(String json) throws Exception {
        Path tempDir = Files.createTempDirectory("aether-registry-test");
        Path modelsPath = tempDir.resolve("models.json");
        if (json != null) {
            Files.writeString(modelsPath, json);
        }
        return new ModelRegistry(AuthStorage.create(tempDir.resolve("auth.json")), modelsPath);
    }
}
