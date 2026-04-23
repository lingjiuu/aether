package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.AgentLoop;
import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.model.AgentConfig;
import io.github.lingjiuu.model.ConversationHistory;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.builtin.GetTimeTool;

import java.util.ArrayList;
import java.util.List;

public class AgentSessionFactory {

    private static final String DEFAULT_PROVIDER = "bailian";
    private static final String DEFAULT_MODEL_ID = "qwen3.5-plus-2026-02-15";
    private static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant";

    private final AgentSessionConfig configuration;

    public AgentSessionFactory(AgentSessionConfig configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        this.configuration = configuration;
    }

    public static AgentSessionFactory createDefault() {
        return createDefault(null, null);
    }

    public static AgentSessionFactory createDefault(String provider, String modelId) {
        AuthStorage authStorage = AuthStorage.create();
        ModelRegistry modelRegistry = new ModelRegistry(authStorage);
        LlmClient llmClient = new LlmClient(modelRegistry);
        LlmModel model = resolveInitialModel(modelRegistry, provider, modelId);

        AgentSessionConfig configuration = AgentSessionConfig.builder()
                .authStorage(authStorage)
                .modelRegistry(modelRegistry)
                .llmClient(llmClient)
                .systemPrompt(DEFAULT_SYSTEM_PROMPT)
                .model(model)
                .defaultTools(buildDefaultTools())
                .build();

        return new AgentSessionFactory(configuration);
    }

    public AgentSession openSession() {
        ToolRegistry toolRegistry = new ToolRegistry();
        List<ToolDefinition> sessionTools = new ArrayList<>();
        if (configuration.getDefaultTools() != null) {
            for (ToolDefinition definition : configuration.getDefaultTools()) {
                toolRegistry.register(definition);
            }
            sessionTools.addAll(toolRegistry.definitions());
        }

        AgentConfig config = AgentConfig.builder()
                .systemPrompt(configuration.getSystemPrompt())
                .model(configuration.getModel())
                .reasoning(configuration.getReasoning())
                .tools(sessionTools)
                .build();
        ConversationHistory history = new ConversationHistory();

        AgentLoop agentLoop = new AgentLoop(
                config,
                configuration.getLlmClient(),
                toolRegistry
        );

        return new AgentSession(
                configuration.getAuthStorage(),
                configuration.getModelRegistry(),
                toolRegistry,
                config,
                history,
                agentLoop
        );
    }

    public AgentSessionConfig configuration() {
        return configuration;
    }

    private static LlmModel resolveInitialModel(ModelRegistry modelRegistry, String provider, String modelId) {
        String resolvedProvider = firstNonBlank(provider, System.getenv("AETHER_PROVIDER"), DEFAULT_PROVIDER);
        String resolvedModelId = firstNonBlank(modelId, System.getenv("AETHER_MODEL"), DEFAULT_MODEL_ID);

        LlmModel explicit = modelRegistry.find(resolvedProvider, resolvedModelId);
        if (explicit != null) {
            return explicit;
        }

        throw new IllegalStateException("No model configured for " + resolvedProvider + "/" + resolvedModelId + ".");
    }

    private static List<ToolDefinition> buildDefaultTools() {
        return List.of(new GetTimeTool());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
