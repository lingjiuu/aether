package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.AgentLoop;
import io.github.lingjiuu.ai.AiModel;
import io.github.lingjiuu.ai.AiStreams;
import io.github.lingjiuu.ai.ModelRegistry;
import io.github.lingjiuu.auth.AuthStorage;
import io.github.lingjiuu.model.AgentState;
import io.github.lingjiuu.model.AgentTool;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.builtin.GetTimeTool;

import java.util.ArrayList;
import java.util.List;

public class AgentSessionFactory {

    private static final String DEFAULT_PROVIDER = "bailian";
    private static final String DEFAULT_MODEL_ID = "qwen3.5-plus-2026-02-15";
    private static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant";

    private final AgentConfiguration configuration;

    public AgentSessionFactory(AgentConfiguration configuration) {
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
        AiStreams aiStreams = new AiStreams();
        AiModel model = resolveInitialModel(modelRegistry, provider, modelId);

        AgentConfiguration configuration = AgentConfiguration.builder()
                .authStorage(authStorage)
                .modelRegistry(modelRegistry)
                .aiStreams(aiStreams)
                .systemPrompt(DEFAULT_SYSTEM_PROMPT)
                .model(model)
                .defaultTools(buildDefaultTools())
                .build();

        return new AgentSessionFactory(configuration);
    }

    public AgentSession openSession() {
        ToolRegistry toolRegistry = new ToolRegistry();
        List<AgentTool> sessionTools = new ArrayList<>();
        if (configuration.getDefaultTools() != null) {
            for (ToolDefinition definition : configuration.getDefaultTools()) {
                toolRegistry.register(definition);
            }
            sessionTools.addAll(toolRegistry.toAgentTools());
        }

        AgentState state = AgentState.builder()
                .systemPrompt(configuration.getSystemPrompt())
                .model(configuration.getModel())
                .reasoning(configuration.getReasoning())
                .tools(sessionTools)
                .build();

        AgentLoop agentLoop = new AgentLoop(
                state,
                configuration.getAiStreams(),
                configuration.getModelRegistry(),
                toolRegistry
        );

        return new AgentSession(
                configuration.getAuthStorage(),
                configuration.getModelRegistry(),
                toolRegistry,
                state,
                agentLoop
        );
    }

    public AgentConfiguration configuration() {
        return configuration;
    }

    private static AiModel resolveInitialModel(ModelRegistry modelRegistry, String provider, String modelId) {
        String resolvedProvider = firstNonBlank(provider, System.getenv("AETHER_PROVIDER"), DEFAULT_PROVIDER);
        String resolvedModelId = firstNonBlank(modelId, System.getenv("AETHER_MODEL"), DEFAULT_MODEL_ID);

        AiModel explicit = modelRegistry.find(resolvedProvider, resolvedModelId);
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
