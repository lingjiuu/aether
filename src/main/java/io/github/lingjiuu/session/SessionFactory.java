package io.github.lingjiuu.session;

import io.github.lingjiuu.infra.config.AetherConfig;
import io.github.lingjiuu.infra.config.AetherConfigException;
import io.github.lingjiuu.infra.config.AetherConfigLoader;
import io.github.lingjiuu.infra.config.AetherPaths;
import io.github.lingjiuu.instructions.AgentsMdInstructions;
import io.github.lingjiuu.instructions.InstructionsManager;
import io.github.lingjiuu.model.client.ModelClient;
import io.github.lingjiuu.model.ModelInfo;
import io.github.lingjiuu.model.ModelOption;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.model.ReasoningOptions;
import io.github.lingjiuu.provider.ProviderEndpoint;
import io.github.lingjiuu.skill.SkillsManager;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.builtin.bash.BashTool;
import io.github.lingjiuu.tool.builtin.edit.EditTool;
import io.github.lingjiuu.tool.builtin.search.GlobTool;
import io.github.lingjiuu.tool.builtin.search.GrepTool;
import io.github.lingjiuu.tool.builtin.read.ReadTool;
import io.github.lingjiuu.tool.builtin.write.WriteTool;
import io.github.lingjiuu.tool.permission.PermissionPreset;
import io.github.lingjiuu.tool.builtin.powershell.PowerShell;
import io.github.lingjiuu.tool.builtin.powershell.PowerShellTool;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import io.github.lingjiuu.trace.AgentTraceRecorder;
import io.github.lingjiuu.trace.sqlite.SqliteTraceStore;
import io.github.lingjiuu.transcript.TranscriptReconstruction;
import io.github.lingjiuu.transcript.TranscriptRestorer;
import io.github.lingjiuu.transcript.TranscriptStore;
import io.github.lingjiuu.transcript.item.SessionMetaItem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

public class SessionFactory {

    private final SessionConfig config;
    private final SkillsManager skillsManager;
    private final Path agentDir;
    private final AetherConfig aetherConfig;

    public SessionFactory(SessionConfig config) {
        this(config, null);
    }

    public SessionFactory(SessionConfig config, SkillsManager skillsManager) {
        this(config, skillsManager, null, null);
    }

    private SessionFactory(
            SessionConfig config,
            SkillsManager skillsManager,
            Path agentDir,
            AetherConfig aetherConfig
    ) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.skillsManager = skillsManager == null ? SkillsManager.empty(config.cwd()) : skillsManager;
        this.agentDir = agentDir == null ? null : agentDir.toAbsolutePath().normalize();
        this.aetherConfig = aetherConfig;
    }

    public static SessionFactory createDefault() {
        return createDefault(null, null);
    }

    public static SessionFactory createDefault(String provider, String modelId) {
        return createDefault(
                provider,
                modelId,
                AetherPaths.getConfigPath()
        );
    }

    static SessionFactory createDefault(
            String provider,
            String modelId,
            Path configPath
    ) {
        return createDefault(
                provider,
                modelId,
                configPath,
                AetherPaths.getAgentDir()
        );
    }

    static SessionFactory createDefault(
            String provider,
            String modelId,
            Path configPath,
            Path agentDir
    ) {
        return createDefault(
                provider,
                modelId,
                configPath,
                agentDir,
                new TranscriptStore(AetherPaths.getTranscriptsDir())
        );
    }

    static SessionFactory createDefault(
            String provider,
            String modelId,
            Path configPath,
            Path agentDir,
            TranscriptStore transcriptStore
    ) {
        AgentTraceRecorder traceRecorder = new AgentTraceRecorder(
                new SqliteTraceStore(AetherPaths.getTraceDbPath())
        );
        AetherConfig aetherConfig = new AetherConfigLoader().load(configPath, provider, modelId);
        ModelSelection modelSelection = aetherConfig.modelSelection();
        ModelClient modelClient = new ModelClient();
        Path defaultCwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path resolvedAgentDir = agentDir == null
                ? AetherPaths.getAgentDir()
                : agentDir.toAbsolutePath().normalize();
        SessionConfig config = buildWorkspaceConfig(
                modelClient,
                modelSelection,
                transcriptStore,
                traceRecorder,
                defaultCwd,
                resolvedAgentDir
        );

        return new SessionFactory(config, new SkillsManager(config.cwd(), resolvedAgentDir), resolvedAgentDir, aetherConfig);
    }

    private static SessionConfig buildWorkspaceConfig(
            ModelClient modelClient,
            ModelSelection modelSelection,
            TranscriptStore transcriptStore,
            AgentTraceRecorder traceRecorder,
            Path cwd,
            Path agentDir
    ) {
        Path resolvedCwd = cwd == null
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : cwd.toAbsolutePath().normalize();
        Path resolvedAgentDir = agentDir == null
                ? AetherPaths.getAgentDir().toAbsolutePath().normalize()
                : agentDir.toAbsolutePath().normalize();
        InstructionsManager instructions = new InstructionsManager(resolvedCwd, resolvedAgentDir);
        AgentsMdInstructions agentsMdInstructions = instructions.loadAgentsMdInstructions();

        List<Tool> tools = buildDefaultTools(resolvedCwd);
        return new SessionConfig(
                modelClient,
                instructions.baseInstructions(),
                instructions.developerInstructions(),
                agentsMdInstructions.text(),
                agentsMdInstructions.sources(),
                resolvedCwd,
                modelSelection,
                transcriptStore,
                traceRecorder,
                tools,
                tools.stream()
                        .map(Tool::name)
                        .toList(),
                PermissionPreset.DEFAULT
        );
    }

    public Session openSession() {
        return openSession(SessionOptions.defaults());
    }

    public Session openSession(SessionOptions options) {
        SessionConfig sessionConfig = sessionConfig(options);
        String sessionId = UUID.randomUUID().toString();
        return new Session(
                sessionConfig,
                sessionId,
                buildSessionMeta(sessionId, sessionConfig),
                skillsManagerFor(sessionConfig)
        );
    }

    public Session resumeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (config.transcriptStore() == null) {
            throw new IllegalStateException("Cannot resume a session without a transcript store.");
        }

        TranscriptReconstruction reconstruction = new TranscriptRestorer(config.transcriptStore()).restore(sessionId);
        reconstruction.validateSessionMetadata();
        SessionConfig sessionConfig;
        try {
            sessionConfig = sessionConfig(SessionOptions.resumeFrom(reconstruction));
        } catch (AetherConfigException e) {
            throw new IllegalStateException("Cannot resume session " + sessionId + ": " + e.getMessage(), e);
        }
        return new Session(
                sessionConfig,
                reconstruction,
                skillsManagerFor(sessionConfig)
        );
    }

    public SessionConfig config() {
        return config;
    }

    public List<ModelOption> modelOptions() {
        if (aetherConfig == null) {
            ModelOption option = ModelOption.from(config.modelSelection());
            return option == null ? List.of() : List.of(option);
        }
        return aetherConfig.modelOptions();
    }

    public List<String> reasoningEfforts() {
        List<String> efforts = new ArrayList<>();
        for (ReasoningOptions.ReasoningEffort effort : ReasoningOptions.ReasoningEffort.values()) {
            efforts.add(effort.name());
        }
        return List.copyOf(efforts);
    }

    public ModelSelection selectModel(
            String explicitProvider,
            String explicitModel,
            String explicitReasoningEffort
    ) {
        if (aetherConfig != null) {
            return aetherConfig.selectModel(explicitProvider, explicitModel, explicitReasoningEffort);
        }
        return configOnlyModelSelection(explicitProvider, explicitModel, explicitReasoningEffort);
    }

    private SessionConfig sessionConfig(SessionOptions options) {
        ModelSelection selection = modelSelection(options);
        if (aetherConfig == null) {
            return config.withModelSelection(selection);
        }
        Path cwd = options == null || options.cwd() == null ? config.cwd() : options.cwd();
        return buildWorkspaceConfig(
                config.modelClient(),
                selection,
                config.transcriptStore(),
                config.traceRecorder(),
                cwd,
                agentDir
        );
    }

    private SkillsManager skillsManagerFor(SessionConfig sessionConfig) {
        if (aetherConfig == null) {
            return skillsManager;
        }
        return new SkillsManager(sessionConfig.cwd(), agentDir);
    }

    private ModelSelection modelSelection(SessionOptions options) {
        if (options == null || !options.hasModelOverride()) {
            return config.modelSelection();
        }
        String provider = firstNonBlank(options.modelProvider(), config.endpoint() == null ? null : config.endpoint().providerId());
        String modelId = firstNonBlank(options.modelId(), config.model() == null ? null : config.model().getId());
        if (aetherConfig != null) {
            return aetherConfig.selectModel(
                    provider,
                    modelId,
                    options.reasoningEffort()
            );
        }
        return configOnlyModelSelection(
                provider,
                modelId,
                options.reasoningEffort()
        );
    }

    static List<Tool> buildDefaultTools(Path cwd) {
        return buildDefaultTools(cwd, isWindows(), BashTool.isAvailable(), PowerShell.isAvailable());
    }

    static List<Tool> buildDefaultTools(
            Path cwd,
            boolean windows,
            boolean bashAvailable,
            boolean powershellAvailable
    ) {
        Path root = cwd == null ? Path.of(System.getProperty("user.dir")) : cwd;
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        List<Tool> tools = new ArrayList<>();
        tools.add(new GlobTool(accessPolicy));
        tools.add(new GrepTool(accessPolicy));
        tools.add(new ReadTool(accessPolicy));
        tools.add(new WriteTool(accessPolicy));
        tools.add(new EditTool(accessPolicy));
        if (windows) {
            if (powershellAvailable) {
                tools.add(new PowerShellTool(accessPolicy));
            }
            if (bashAvailable) {
                tools.add(new BashTool(accessPolicy));
            }
        } else {
            tools.add(new BashTool(accessPolicy));
        }
        return List.copyOf(tools);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private ModelSelection configOnlyModelSelection(
            String explicitProvider,
            String explicitModel,
            String explicitReasoningEffort
    ) {
        return AetherConfig.selectCurrentModel(
                config.model(),
                config.endpoint(),
                config.requestAuth(),
                config.reasoning(),
                explicitProvider,
                explicitModel,
                explicitReasoningEffort
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

    private SessionMetaItem buildSessionMeta(String sessionId, SessionConfig config) {
        ModelInfo model = config.model();
        ProviderEndpoint endpoint = config.endpoint();
        List<String> activeTools = config.activeToolNames() == null ? List.of() : config.activeToolNames();
        String systemPromptHash = sha256(config.baseInstructions());
        return SessionMetaItem.builder()
                .sessionId(sessionId)
                .createdAt(System.currentTimeMillis())
                .cwd(config.cwd() == null ? null : config.cwd().toString())
                .modelProvider(endpoint == null ? null : endpoint.providerId())
                .modelId(model == null ? null : model.getId())
                .modelApi(endpoint == null ? null : endpoint.wireApi())
                .modelBaseUrl(endpoint == null ? null : endpoint.baseUrl())
                .modelContextWindowTokens(model == null ? null : model.getContextWindowTokens())
                .modelAutoCompactTokenLimit(model == null ? null : model.getAutoCompactTokenLimit())
                .reasoningEffort(config.reasoning() == null ? null : config.reasoning().effortName())
                .systemPromptHash(systemPromptHash)
                .activeToolNames(activeTools)
                .configFingerprint(configFingerprint(config, systemPromptHash, activeTools))
                .aetherVersion(aetherVersion())
                .build();
    }

    private String configFingerprint(SessionConfig config, String systemPromptHash, List<String> activeToolNames) {
        ModelInfo model = config.model();
        ProviderEndpoint endpoint = config.endpoint();
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("cwd=" + nullToEmpty(config.cwd() == null ? null : config.cwd().toString()));
        joiner.add("modelProvider=" + nullToEmpty(endpoint == null ? null : endpoint.providerId()));
        joiner.add("modelId=" + nullToEmpty(model == null ? null : model.getId()));
        joiner.add("modelApi=" + nullToEmpty(endpoint == null ? null : endpoint.wireApi()));
        joiner.add("modelBaseUrl=" + nullToEmpty(endpoint == null ? null : endpoint.baseUrl()));
        joiner.add("modelContextWindowTokens=" + nullToEmpty(model == null ? null : String.valueOf(model.getContextWindowTokens())));
        joiner.add("modelAutoCompactTokenLimit=" + nullToEmpty(model == null ? null : String.valueOf(model.getAutoCompactTokenLimit())));
        joiner.add("reasoningEffort=" + nullToEmpty(config.reasoning() == null ? null : config.reasoning().effortName()));
        joiner.add("systemPromptHash=" + nullToEmpty(systemPromptHash));
        joiner.add("activeToolNames=" + String.join(",", activeToolNames == null ? List.of() : activeToolNames));
        return sha256(joiner.toString());
    }

    private String aetherVersion() {
        String version = SessionFactory.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(nullToEmpty(text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

}
