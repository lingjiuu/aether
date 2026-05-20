package io.github.lingjiuu.session;

import io.github.lingjiuu.infra.config.AetherConfig;
import io.github.lingjiuu.infra.config.AetherConfigException;
import io.github.lingjiuu.infra.config.AetherConfigLoader;
import io.github.lingjiuu.infra.config.AetherPaths;
import io.github.lingjiuu.infra.config.ConfigValueResolver;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.ReasoningOptions;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.resource.PromptResources;
import io.github.lingjiuu.resource.ResourceLoader;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.tools.BashTool;
import io.github.lingjiuu.tool.tools.EditTool;
import io.github.lingjiuu.tool.tools.FindTool;
import io.github.lingjiuu.tool.tools.GrepTool;
import io.github.lingjiuu.tool.tools.LsTool;
import io.github.lingjiuu.tool.tools.ReadTool;
import io.github.lingjiuu.tool.tools.WriteTool;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;

public class SessionFactory {

    private static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant";

    private final SessionConfig config;

    public SessionFactory(SessionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
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
                Path.of(System.getProperty("user.dir")),
                AetherPaths.getAgentDir()
        );
    }

    static SessionFactory createDefault(
            String provider,
            String modelId,
            Path configPath,
            Path cwd,
            Path agentDir
    ) {
        AetherConfig aetherConfig = new AetherConfigLoader().load(configPath);
        ModelSelection modelSelection = resolveModelSelection(aetherConfig, provider, modelId);
        LlmClient llmClient = new LlmClient();
        Path resolvedCwd = cwd == null
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : cwd.toAbsolutePath().normalize();
        Path resolvedAgentDir = agentDir == null
                ? AetherPaths.getAgentDir()
                : agentDir.toAbsolutePath().normalize();
        PromptResources promptResources = new ResourceLoader(resolvedCwd, resolvedAgentDir).load();
        String systemPrompt = promptResources.getSystemPrompt() == null || promptResources.getSystemPrompt().isBlank()
                ? DEFAULT_SYSTEM_PROMPT
                : promptResources.getSystemPrompt();

        List<ToolDefinition> toolDefinitions = buildDefaultTools(resolvedCwd);
        SessionConfig config = new SessionConfig(
                llmClient,
                systemPrompt,
                resolvedCwd,
                modelSelection.model(),
                modelSelection.auth(),
                modelSelection.reasoning(),
                new TranscriptStore(AetherPaths.getTranscriptsDir()),
                toolDefinitions,
                promptResources,
                toolDefinitions.stream()
                        .map(ToolDefinition::name)
                        .toList()
        );

        return new SessionFactory(config);
    }

    public Session openSession() {
        String sessionId = UUID.randomUUID().toString();
        return new SessionBuilder()
                .config(config)
                .sessionId(sessionId)
                .sessionMeta(buildSessionMeta(sessionId))
                .recordSessionMeta(true)
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
        return new SessionBuilder()
                .config(config)
                .toolRegistry(buildToolRegistry())
                .sessionId(sessionId)
                .initialMessages(reconstruction.messages())
                .lastTranscriptRecordId(reconstruction.lastRecordId())
                .recordSessionMeta(false)
                .build();
    }

    public SessionConfig config() {
        return config;
    }

    private ToolRegistry buildToolRegistry() {
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
                reasoningFrom(config.defaultThinkingLevel())
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

    private SessionMetaItem buildSessionMeta(String sessionId) {
        LlmModel model = config.model();
        List<String> activeTools = config.activeToolNames() == null ? List.of() : config.activeToolNames();
        String systemPromptHash = sha256(config.systemPrompt());
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
                .systemPromptHash(systemPromptHash)
                .activeToolNames(activeTools)
                .configFingerprint(configFingerprint(systemPromptHash, activeTools))
                .aetherVersion(aetherVersion())
                .build();
    }

    private String configFingerprint(String systemPromptHash, List<String> activeToolNames) {
        LlmModel model = config.model();
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("cwd=" + nullToEmpty(config.cwd() == null ? null : config.cwd().toString()));
        joiner.add("modelProvider=" + nullToEmpty(model == null ? null : model.getProvider()));
        joiner.add("modelId=" + nullToEmpty(model == null ? null : model.getId()));
        joiner.add("modelApi=" + nullToEmpty(model == null ? null : model.getApi()));
        joiner.add("modelBaseUrl=" + nullToEmpty(model == null ? null : model.getBaseUrl()));
        joiner.add("modelContextWindowTokens=" + nullToEmpty(model == null ? null : String.valueOf(model.getContextWindowTokens())));
        joiner.add("modelAutoCompactTokenLimit=" + nullToEmpty(model == null ? null : String.valueOf(model.getAutoCompactTokenLimit())));
        joiner.add("systemPromptHash=" + nullToEmpty(systemPromptHash));
        joiner.add("activeToolNames=" + String.join(",", activeToolNames == null ? List.of() : activeToolNames));
        return sha256(joiner.toString());
    }

    private void validateResumeMetadata(TranscriptReconstruction reconstruction) {
        SessionMetaItem actual = reconstruction.sessionMeta();
        if (actual == null) {
            throw new IllegalStateException("Cannot resume session " + reconstruction.sessionId() + " because transcript has no session metadata.");
        }

        SessionMetaItem expected = buildSessionMeta(reconstruction.sessionId());
        List<String> differences = new ArrayList<>();
        compare(differences, "sessionId", actual.getSessionId(), expected.getSessionId());
        compare(differences, "cwd", actual.getCwd(), expected.getCwd());
        compare(differences, "modelProvider", actual.getModelProvider(), expected.getModelProvider());
        compare(differences, "modelId", actual.getModelId(), expected.getModelId());
        compare(differences, "modelApi", actual.getModelApi(), expected.getModelApi());
        compare(differences, "modelBaseUrl", actual.getModelBaseUrl(), expected.getModelBaseUrl());
        compare(differences, "modelContextWindowTokens", actual.getModelContextWindowTokens(), expected.getModelContextWindowTokens());
        compare(differences, "modelAutoCompactTokenLimit", actual.getModelAutoCompactTokenLimit(), expected.getModelAutoCompactTokenLimit());
        compare(differences, "systemPromptHash", actual.getSystemPromptHash(), expected.getSystemPromptHash());
        compare(differences, "activeToolNames", actual.getActiveToolNames(), expected.getActiveToolNames());
        compare(differences, "configFingerprint", actual.getConfigFingerprint(), expected.getConfigFingerprint());
        if (!differences.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot resume session " + reconstruction.sessionId() + " because transcript metadata does not match current session config:\n- "
                            + String.join("\n- ", differences)
            );
        }
    }

    private void compare(List<String> differences, String field, Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            differences.add(field + " transcript=" + actual + " current=" + expected);
        }
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
