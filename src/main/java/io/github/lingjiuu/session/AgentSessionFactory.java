package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.turn.AgentLoop;
import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.infra.config.AetherPaths;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.builtin.FindTool;
import io.github.lingjiuu.tool.builtin.GrepTool;
import io.github.lingjiuu.tool.builtin.LsTool;
import io.github.lingjiuu.tool.builtin.ReadTool;
import io.github.lingjiuu.tool.fs.FileAccessPolicy;
import io.github.lingjiuu.transcript.RestoredTranscript;
import io.github.lingjiuu.transcript.TranscriptRestorer;
import io.github.lingjiuu.transcript.TranscriptStore;

import java.nio.file.Path;
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
                .toolDefinitions(buildDefaultTools())
                .activeToolNames(List.of("ls", "find", "grep", "read"))
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
        FileAccessPolicy accessPolicy = FileAccessPolicy.rootedAt(Path.of(System.getProperty("user.dir")));
        return List.of(
                new LsTool(accessPolicy),
                new FindTool(accessPolicy),
                new GrepTool(accessPolicy),
                new ReadTool(accessPolicy)
        );
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
