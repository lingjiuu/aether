package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.turn.AgentLoop;
import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.infra.config.AetherPaths;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.ReasoningOptions;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.tools.DefaultTools;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import io.github.lingjiuu.transcript.RestoredTranscript;
import io.github.lingjiuu.transcript.TranscriptRestorer;
import io.github.lingjiuu.transcript.TranscriptStore;

import java.nio.file.Path;
import java.util.List;

public class AgentSessionFactory {

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
        return createDefault(
                provider,
                modelId,
                AetherPaths.getAuthPath(),
                AetherPaths.getModelsPath(),
                AetherPaths.getSettingsPath()
        );
    }

    static AgentSessionFactory createDefault(
            String provider,
            String modelId,
            Path authPath,
            Path modelsPath,
            Path settingsPath
    ) {
        AuthStorage authStorage = AuthStorage.create(authPath);
        ModelRegistry modelRegistry = new ModelRegistry(authStorage, modelsPath);
        SettingsManager settingsManager = SettingsManager.create(settingsPath);
        LlmClient llmClient = new LlmClient(modelRegistry);
        LlmModel model = new ModelResolver().findInitialModel(modelRegistry, settingsManager, provider, modelId);

        AgentSessionConfig configuration = AgentSessionConfig.builder()
                .authStorage(authStorage)
                .modelRegistry(modelRegistry)
                .settingsManager(settingsManager)
                .llmClient(llmClient)
                .systemPrompt(DEFAULT_SYSTEM_PROMPT)
                .model(model)
                .reasoning(defaultReasoning(settingsManager))
                .toolDefinitions(buildDefaultTools())
                .activeToolNames(DefaultTools.defaultNames())
                .transcriptStore(new TranscriptStore(AetherPaths.getTranscriptsDir()))
                .build();

        return new AgentSessionFactory(configuration);
    }

    public AgentSession openSession() {
        AgentSessionServices services = buildSessionServices();
        return new AgentSession(services, new AgentLoop(services));
    }

    public AgentSession resumeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (configuration.getTranscriptStore() == null) {
            throw new IllegalStateException("Cannot resume a session without a transcript store.");
        }

        AgentSessionServices services = buildSessionServices();
        RestoredTranscript restoredTranscript = new TranscriptRestorer(configuration.getTranscriptStore()).restore(sessionId);
        return new AgentSession(
                services,
                new AgentLoop(services),
                sessionId,
                restoredTranscript.getChain().messages(),
                restoredTranscript.getLastRecordId()
        );
    }

    private AgentSessionServices buildSessionServices() {
        ToolRegistry toolRegistry = new ToolRegistry();
        if (configuration.getToolDefinitions() != null) {
            for (ToolDefinition definition : configuration.getToolDefinitions()) {
                toolRegistry.register(definition);
            }
        }

        return new AgentSessionServices(
                configuration,
                configuration.getModelRegistry(),
                toolRegistry,
                configuration.getLlmClient()
        );
    }

    public AgentSessionConfig configuration() {
        return configuration;
    }

    private static List<ToolDefinition> buildDefaultTools() {
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(Path.of(System.getProperty("user.dir")));
        return DefaultTools.createAll(accessPolicy);
    }

    private static ReasoningOptions defaultReasoning(SettingsManager settingsManager) {
        ReasoningOptions.ReasoningEffort effort = settingsManager.getDefaultThinkingLevel();
        return effort == null ? null : ReasoningOptions.builder()
                .reasoningEffort(effort)
                .build();
    }
}
