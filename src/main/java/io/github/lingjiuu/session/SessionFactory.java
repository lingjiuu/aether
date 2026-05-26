package io.github.lingjiuu.session;

import io.github.lingjiuu.infra.config.AetherConfig;
import io.github.lingjiuu.infra.config.AetherConfigException;
import io.github.lingjiuu.infra.config.AetherConfigLoader;
import io.github.lingjiuu.infra.config.AetherPaths;
import io.github.lingjiuu.infra.config.ConfigValueResolver;
import io.github.lingjiuu.instructions.AgentsMdInstructions;
import io.github.lingjiuu.instructions.AgentsMdManager;
import io.github.lingjiuu.instructions.BaseInstructions;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.ReasoningOptions;
import io.github.lingjiuu.llm.ModelOption;
import io.github.lingjiuu.llm.ModelSelection;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.skill.SkillsManager;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.builtin.BashTool;
import io.github.lingjiuu.tool.builtin.EditTool;
import io.github.lingjiuu.tool.builtin.FindTool;
import io.github.lingjiuu.tool.builtin.GrepTool;
import io.github.lingjiuu.tool.builtin.LsTool;
import io.github.lingjiuu.tool.builtin.ReadTool;
import io.github.lingjiuu.tool.builtin.WriteTool;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import io.github.lingjiuu.transcript.TranscriptReconstruction;
import io.github.lingjiuu.transcript.TranscriptModelSelection;
import io.github.lingjiuu.transcript.TranscriptRestorer;
import io.github.lingjiuu.transcript.TranscriptStore;
import io.github.lingjiuu.transcript.item.SessionMetaItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;

public class SessionFactory {

    private static final List<String> PROJECT_INSTRUCTION_DIR_NAMES = List.of(".aether", ".agent");

    private final SessionConfig config;
    private final SkillsManager skillsManager;
    private final Path agentDir;
    private final boolean workspaceConfigurable;
    private final AetherConfig aetherConfig;

    public SessionFactory(SessionConfig config) {
        this(config, null);
    }

    public SessionFactory(SessionConfig config, SkillsManager skillsManager) {
        this(config, skillsManager, null, false, null);
    }

    private SessionFactory(
            SessionConfig config,
            SkillsManager skillsManager,
            Path agentDir,
            boolean workspaceConfigurable,
            AetherConfig aetherConfig
    ) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.skillsManager = skillsManager == null ? SkillsManager.empty(config.cwd()) : skillsManager;
        this.agentDir = agentDir == null ? null : agentDir.toAbsolutePath().normalize();
        this.workspaceConfigurable = workspaceConfigurable;
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
        AetherConfig aetherConfig = new AetherConfigLoader().load(configPath);
        ModelSelection modelSelection = resolveModelSelection(aetherConfig, provider, modelId);
        LlmClient llmClient = new LlmClient();
        Path defaultCwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path resolvedAgentDir = agentDir == null
                ? AetherPaths.getAgentDir()
                : agentDir.toAbsolutePath().normalize();
        SessionBundle bundle = buildWorkspaceBundle(
                llmClient,
                modelSelection.model(),
                modelSelection.auth(),
                modelSelection.reasoning(),
                transcriptStore,
                defaultCwd,
                resolvedAgentDir
        );

        return new SessionFactory(bundle.config(), bundle.skillsManager(), resolvedAgentDir, true, aetherConfig);
    }

    private static SessionBundle buildWorkspaceBundle(
            LlmClient llmClient,
            LlmModel model,
            RequestAuth requestAuth,
            ReasoningOptions reasoning,
            TranscriptStore transcriptStore,
            Path cwd,
            Path agentDir
    ) {
        Path resolvedCwd = cwd == null
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : cwd.toAbsolutePath().normalize();
        Path resolvedAgentDir = agentDir == null
                ? AetherPaths.getAgentDir().toAbsolutePath().normalize()
                : agentDir.toAbsolutePath().normalize();
        AgentsMdInstructions agentsMdInstructions = new AgentsMdManager(resolvedCwd, resolvedAgentDir).load();
        SkillsManager skillsManager = new SkillsManager(resolvedCwd, resolvedAgentDir);
        String baseInstructions = baseInstructions(resolvedCwd, resolvedAgentDir);
        String developerInstructions = readInstructionFile(resolvedCwd, resolvedAgentDir, "APPEND_SYSTEM.md");

        List<ToolDefinition> toolDefinitions = buildDefaultTools(resolvedCwd);
        SessionConfig config = new SessionConfig(
                llmClient,
                baseInstructions,
                developerInstructions,
                agentsMdInstructions.text(),
                agentsMdInstructions.sources(),
                resolvedCwd,
                model,
                requestAuth,
                reasoning,
                transcriptStore,
                toolDefinitions,
                toolDefinitions.stream()
                        .map(ToolDefinition::name)
                        .toList()
        );

        return new SessionBundle(config, skillsManager);
    }

    public Session openSession() {
        return openSession(SessionOptions.defaults());
    }

    private static String baseInstructions(Path cwd, Path agentDir) {
        String configured = readInstructionFile(cwd, agentDir, "SYSTEM.md");
        return configured == null || configured.isBlank()
                ? BaseInstructions.DEFAULT
                : configured.trim();
    }

    private static String readInstructionFile(Path cwd, Path agentDir, String fileName) {
        for (Path candidate : instructionFileCandidates(cwd, agentDir, fileName)) {
            if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8).trim();
                } catch (IOException ignored) {
                    return "";
                }
            }
        }
        return "";
    }

    private static List<Path> instructionFileCandidates(Path cwd, Path agentDir, String fileName) {
        List<Path> candidates = new ArrayList<>();
        Path resolvedCwd = cwd == null
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : cwd.toAbsolutePath().normalize();
        for (String dirName : PROJECT_INSTRUCTION_DIR_NAMES) {
            candidates.add(resolvedCwd.resolve(dirName).resolve(fileName));
        }
        if (agentDir != null) {
            candidates.add(agentDir.toAbsolutePath().normalize().resolve(fileName));
        }
        return List.copyOf(candidates);
    }

    public Session openSession(SessionOptions options) {
        SessionBundle bundle = sessionBundle(options);
        String sessionId = UUID.randomUUID().toString();
        return new SessionBuilder()
                .config(bundle.config())
                .sessionId(sessionId)
                .sessionMeta(buildSessionMeta(sessionId, bundle.config()))
                .recordSessionMeta(true)
                .skillsManager(bundle.skillsManager())
                .build();
    }

    public Session resumeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (config.transcriptStore() == null) {
            throw new IllegalStateException("Cannot resume a session without a transcript store.");
        }

        TranscriptReconstruction reconstruction = new TranscriptRestorer(config.transcriptStore()).restore(sessionId);
        validateResumeMetadata(reconstruction);
        SessionBundle bundle = resumeSessionBundle(sessionId, resumeOptions(reconstruction));
        return new SessionBuilder()
                .config(bundle.config())
                .toolRegistry(buildToolRegistry(bundle.config()))
                .sessionId(sessionId)
                .sessionName(reconstruction.sessionName())
                .initialMessages(reconstruction.messages())
                .initialContextBaseline(reconstruction.initialContextBaseline())
                .initialTimelineEvents(reconstruction.timelineEvents())
                .initialEventSequence(reconstruction.lastEventSequence())
                .lastTranscriptRecordId(reconstruction.lastRecordId())
                .recordSessionMeta(false)
                .skillsManager(bundle.skillsManager())
                .build();
    }

    public SessionConfig config() {
        return config;
    }

    public List<ModelOption> modelOptions() {
        if (aetherConfig == null) {
            ModelOption option = modelOption(config.model());
            return option == null ? List.of() : List.of(option);
        }
        List<ModelOption> options = new ArrayList<>();
        for (Map.Entry<String, AetherConfig.ModelProviderConfig> entry : aetherConfig.modelProviders().entrySet()) {
            String providerId = entry.getKey();
            AetherConfig.ModelProviderConfig provider = entry.getValue();
            if (provider == null || provider.models() == null) {
                continue;
            }
            for (AetherConfig.ModelDefinition model : provider.models()) {
                if (model != null) {
                    options.add(modelOption(providerId, provider, model));
                }
            }
        }
        return List.copyOf(options);
    }

    public List<String> reasoningEfforts() {
        List<String> efforts = new ArrayList<>();
        for (ReasoningOptions.ReasoningEffort effort : ReasoningOptions.ReasoningEffort.values()) {
            efforts.add(effort.name());
        }
        return List.copyOf(efforts);
    }

    public ModelSelection resolveModelSelection(
            String explicitProvider,
            String explicitModel,
            String explicitReasoningEffort
    ) {
        if (aetherConfig != null) {
            return resolveModelSelection(aetherConfig, explicitProvider, explicitModel, explicitReasoningEffort);
        }
        return resolveConfiguredModelSelection(explicitProvider, explicitModel, explicitReasoningEffort);
    }

    private SessionBundle sessionBundle(SessionOptions options) {
        ModelSelection selection = modelSelection(options);
        if (!workspaceConfigurable) {
            return new SessionBundle(config.withModelSelection(selection), skillsManager);
        }
        Path cwd = options == null || options.cwd() == null ? config.cwd() : options.cwd();
        return buildWorkspaceBundle(
                config.llmClient(),
                selection.model(),
                selection.auth(),
                selection.reasoning(),
                config.transcriptStore(),
                cwd,
                agentDir
        );
    }

    private SessionBundle resumeSessionBundle(String sessionId, SessionOptions options) {
        try {
            return sessionBundle(options);
        } catch (AetherConfigException e) {
            throw new IllegalStateException("Cannot resume session " + sessionId + ": " + e.getMessage(), e);
        }
    }

    private ModelSelection modelSelection(SessionOptions options) {
        if (!hasModelSelection(options)) {
            return config.modelSelection();
        }
        String provider = firstNonBlank(options.modelProvider(), config.model() == null ? null : config.model().getProvider());
        String modelId = firstNonBlank(options.modelId(), config.model() == null ? null : config.model().getId());
        if (aetherConfig != null) {
            return resolveModelSelection(
                    aetherConfig,
                    provider,
                    modelId,
                    options.reasoningEffort()
            );
        }
        return resolveConfiguredModelSelection(
                provider,
                modelId,
                options.reasoningEffort()
        );
    }

    private boolean hasModelSelection(SessionOptions options) {
        return options != null
                && (!isBlank(options.modelProvider())
                || !isBlank(options.modelId())
                || !isBlank(options.reasoningEffort()));
    }

    private SessionOptions resumeOptions(TranscriptReconstruction reconstruction) {
        SessionMetaItem meta = reconstruction == null ? null : reconstruction.sessionMeta();
        TranscriptModelSelection selection = reconstruction == null ? null : reconstruction.modelSelection();
        Path cwd = meta == null || meta.getCwd() == null || meta.getCwd().isBlank()
                ? null
                : Path.of(meta.getCwd());
        return SessionOptions.resume(
                cwd,
                selection == null ? null : selection.providerId(),
                selection == null ? null : selection.modelId(),
                selection == null ? null : selection.reasoningEffort()
        );
    }

    private ToolRegistry buildToolRegistry(SessionConfig config) {
        ToolRegistry toolRegistry = new ToolRegistry();
        for (ToolDefinition definition : config.toolDefinitions()) {
            toolRegistry.register(definition);
        }
        return toolRegistry;
    }

    private static List<ToolDefinition> buildDefaultTools(Path cwd) {
        Path root = cwd == null ? Path.of(System.getProperty("user.dir")) : cwd;
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(root);
        return List.of(
                new LsTool(accessPolicy),
                new FindTool(accessPolicy),
                new GrepTool(accessPolicy),
                new ReadTool(accessPolicy),
                new WriteTool(accessPolicy),
                new EditTool(accessPolicy),
                new BashTool(accessPolicy)
        );
    }

    private static ModelSelection resolveModelSelection(
            AetherConfig config,
            String explicitProvider,
            String explicitModel
    ) {
        return resolveModelSelection(config, explicitProvider, explicitModel, null);
    }

    private static ModelSelection resolveModelSelection(
            AetherConfig config,
            String explicitProvider,
            String explicitModel,
            String explicitReasoningEffort
    ) {
        String providerId = blankToNull(explicitProvider);
        String modelId = blankToNull(explicitModel);
        if (modelId != null) {
            int slash = modelId.indexOf('/');
            if (slash > 0 && slash < modelId.length() - 1) {
                providerId = modelId.substring(0, slash);
                modelId = modelId.substring(slash + 1);
            }
        } else if (providerId != null) {
            throw new AetherConfigException("A model id is required when provider \"" + providerId + "\" is specified.");
        } else {
            providerId = config.defaultProvider();
            modelId = config.defaultModel();
        }

        AetherConfig.ModelProviderConfig provider = config.modelProviders().get(providerId);
        if (provider == null) {
            throw new AetherConfigException("Model provider \"" + providerId + "\" is not configured.");
        }
        AetherConfig.ModelDefinition modelDefinition = findModel(provider, modelId);
        if (modelDefinition == null) {
            throw new AetherConfigException("Model \"" + providerId + "/" + modelId + "\" is not configured.");
        }

        String api = firstNonBlank(modelDefinition.api(), provider.api());
        String baseUrl = firstNonBlank(modelDefinition.baseUrl(), provider.baseUrl());
        LlmModel model = LlmModel.builder()
                .provider(providerId)
                .api(api)
                .id(modelDefinition.id())
                .name(firstNonBlank(modelDefinition.name(), modelDefinition.id()))
                .baseUrl(baseUrl)
                .contextWindowTokens(modelDefinition.contextWindowTokens())
                .autoCompactTokenLimit(modelDefinition.autoCompactTokenLimit())
                .headers(mergeHeaders(provider.headers(), modelDefinition.headers()))
                .input(modelDefinition.input())
                .build();
        return new ModelSelection(
                model,
                resolveRequestAuth(providerId, provider, modelDefinition),
                reasoningFrom(selectedReasoningValue(config, explicitReasoningEffort))
        );
    }

    private ModelSelection resolveConfiguredModelSelection(
            String explicitProvider,
            String explicitModel,
            String explicitReasoningEffort
    ) {
        LlmModel model = config.model();
        if (model == null) {
            throw new AetherConfigException("No model is configured for this session.");
        }
        String providerId = blankToNull(explicitProvider);
        String modelId = blankToNull(explicitModel);
        if (modelId != null) {
            int slash = modelId.indexOf('/');
            if (slash > 0 && slash < modelId.length() - 1) {
                providerId = modelId.substring(0, slash);
                modelId = modelId.substring(slash + 1);
            }
        }
        if (providerId == null) {
            providerId = model.getProvider();
        }
        if (modelId == null) {
            modelId = model.getId();
        }
        if (!Objects.equals(providerId, model.getProvider()) || !Objects.equals(modelId, model.getId())) {
            throw new AetherConfigException("Model \"" + providerId + "/" + modelId + "\" is not configured.");
        }
        return new ModelSelection(
                model,
                config.requestAuth(),
                reasoningFromOrDefault(explicitReasoningEffort, config.reasoning())
        );
    }

    private static AetherConfig.ModelDefinition findModel(AetherConfig.ModelProviderConfig provider, String modelId) {
        for (AetherConfig.ModelDefinition model : provider.models()) {
            if (model != null && model.id().equals(modelId)) {
                return model;
            }
        }
        return null;
    }

    private static RequestAuth resolveRequestAuth(
            String providerId,
            AetherConfig.ModelProviderConfig provider,
            AetherConfig.ModelDefinition model
    ) {
        String apiKey = null;
        if (!isBlank(provider.apiKey())) {
            apiKey = ConfigValueResolver.resolveConfigValueOrThrow(
                    provider.apiKey(),
                    "API key for provider \"" + providerId + "\""
            );
        }

        Map<String, String> headers = resolveHeaders(providerId, provider, model);
        if (Boolean.TRUE.equals(provider.authHeader())) {
            if (isBlank(apiKey)) {
                throw new AetherConfigException("Provider \"" + providerId + "\" enables auth_header but api_key is missing.");
            }
            headers.put("Authorization", "Bearer " + apiKey);
        }

        if (isBlank(apiKey)) {
            throw new AetherConfigException("Provider \"" + providerId + "\" must define api_key.");
        }
        return RequestAuth.ok(apiKey, headers.isEmpty() ? null : headers);
    }

    private static Map<String, String> resolveHeaders(
            String providerId,
            AetherConfig.ModelProviderConfig provider,
            AetherConfig.ModelDefinition model
    ) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> providerHeaders = ConfigValueResolver.resolveHeadersOrThrow(
                provider.headers(),
                "provider \"" + providerId + "\""
        );
        if (providerHeaders != null) {
            headers.putAll(providerHeaders);
        }
        Map<String, String> modelHeaders = ConfigValueResolver.resolveHeadersOrThrow(
                model.headers(),
                "model \"" + providerId + "/" + model.id() + "\""
        );
        if (modelHeaders != null) {
            headers.putAll(modelHeaders);
        }
        return headers;
    }

    private static Map<String, String> mergeHeaders(Map<String, String> providerHeaders, Map<String, String> modelHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (providerHeaders != null) {
            headers.putAll(providerHeaders);
        }
        if (modelHeaders != null) {
            headers.putAll(modelHeaders);
        }
        return headers;
    }

    private static ReasoningOptions reasoningFrom(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        ReasoningOptions.ReasoningEffort effort;
        try {
            effort = ReasoningOptions.ReasoningEffort.valueOf(normalized.trim().replace('-', '_').toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AetherConfigException("Unknown default_thinking_level: " + value, e);
        }
        return ReasoningOptions.builder()
                .reasoningEffort(effort)
                .build();
    }

    private static ReasoningOptions reasoningFromOrDefault(String value, ReasoningOptions defaultReasoning) {
        String normalized = blankToNull(value);
        if (normalized == null || "default".equalsIgnoreCase(normalized)) {
            return defaultReasoning;
        }
        return reasoningFrom(normalized);
    }

    private static String selectedReasoningValue(AetherConfig config, String explicitReasoningEffort) {
        String normalized = blankToNull(explicitReasoningEffort);
        if (normalized == null || "default".equalsIgnoreCase(normalized)) {
            return config.defaultThinkingLevel();
        }
        return normalized;
    }

    private static ModelOption modelOption(LlmModel model) {
        if (model == null) {
            return null;
        }
        return new ModelOption(
                model.getProvider(),
                model.getId(),
                model.getName(),
                model.getApi(),
                model.getContextWindowTokens(),
                model.getAutoCompactTokenLimit(),
                model.getInput()
        );
    }

    private static ModelOption modelOption(
            String providerId,
            AetherConfig.ModelProviderConfig provider,
            AetherConfig.ModelDefinition model
    ) {
        String api = firstNonBlank(model.api(), provider.api());
        return new ModelOption(
                providerId,
                model.id(),
                firstNonBlank(model.name(), model.id()),
                api,
                model.contextWindowTokens(),
                model.autoCompactTokenLimit(),
                model.input()
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private SessionMetaItem buildSessionMeta(String sessionId, SessionConfig config) {
        LlmModel model = config.model();
        List<String> activeTools = config.activeToolNames() == null ? List.of() : config.activeToolNames();
        String systemPromptHash = sha256(config.baseInstructions());
        return SessionMetaItem.builder()
                .sessionId(sessionId)
                .createdAt(System.currentTimeMillis())
                .cwd(config.cwd() == null ? null : config.cwd().toString())
                .modelProvider(model == null ? null : model.getProvider())
                .modelId(model == null ? null : model.getId())
                .modelApi(model == null ? null : model.getApi())
                .modelBaseUrl(model == null ? null : model.getBaseUrl())
                .modelContextWindowTokens(model == null ? null : model.getContextWindowTokens())
                .modelAutoCompactTokenLimit(model == null ? null : model.getAutoCompactTokenLimit())
                .reasoningEffort(reasoningEffort(config.reasoning()))
                .systemPromptHash(systemPromptHash)
                .activeToolNames(activeTools)
                .configFingerprint(configFingerprint(config, systemPromptHash, activeTools))
                .aetherVersion(aetherVersion())
                .build();
    }

    private String configFingerprint(SessionConfig config, String systemPromptHash, List<String> activeToolNames) {
        LlmModel model = config.model();
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("cwd=" + nullToEmpty(config.cwd() == null ? null : config.cwd().toString()));
        joiner.add("modelProvider=" + nullToEmpty(model == null ? null : model.getProvider()));
        joiner.add("modelId=" + nullToEmpty(model == null ? null : model.getId()));
        joiner.add("modelApi=" + nullToEmpty(model == null ? null : model.getApi()));
        joiner.add("modelBaseUrl=" + nullToEmpty(model == null ? null : model.getBaseUrl()));
        joiner.add("modelContextWindowTokens=" + nullToEmpty(model == null ? null : String.valueOf(model.getContextWindowTokens())));
        joiner.add("modelAutoCompactTokenLimit=" + nullToEmpty(model == null ? null : String.valueOf(model.getAutoCompactTokenLimit())));
        joiner.add("reasoningEffort=" + nullToEmpty(reasoningEffort(config.reasoning())));
        joiner.add("systemPromptHash=" + nullToEmpty(systemPromptHash));
        joiner.add("activeToolNames=" + String.join(",", activeToolNames == null ? List.of() : activeToolNames));
        return sha256(joiner.toString());
    }

    private void validateResumeMetadata(TranscriptReconstruction reconstruction) {
        SessionMetaItem actual = reconstruction.sessionMeta();
        if (actual == null) {
            throw new IllegalStateException("Cannot resume session " + reconstruction.sessionId() + " because transcript has no session metadata.");
        }
        if (actual.getSessionId() != null && !Objects.equals(actual.getSessionId(), reconstruction.sessionId())) {
            throw new IllegalStateException(
                    "Cannot resume session " + reconstruction.sessionId() + " because transcript metadata belongs to session "
                            + actual.getSessionId() + "."
            );
        }
    }

    private static String reasoningEffort(ReasoningOptions reasoning) {
        return reasoning == null || reasoning.getReasoningEffort() == null
                ? null
                : reasoning.getReasoningEffort().name();
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

    private record SessionBundle(SessionConfig config, SkillsManager skillsManager) {
    }
}
