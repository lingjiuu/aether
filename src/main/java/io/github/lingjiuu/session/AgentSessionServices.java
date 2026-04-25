package io.github.lingjiuu.session;

import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.tool.ToolRunner;
import io.github.lingjiuu.tool.ToolPoolCompiler;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.hook.ToolHookChain;
import io.github.lingjiuu.tool.permission.DefaultToolPermissionService;
import io.github.lingjiuu.tool.permission.ToolPermissionService;
import io.github.lingjiuu.transcript.TranscriptStore;
import lombok.Getter;

@Getter
public class AgentSessionServices {

    private final AgentSessionConfig config;
    private final ModelRegistry modelRegistry;
    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;
    private final TranscriptStore transcriptStore;
    private final ToolPoolCompiler toolPoolCompiler;
    private final SystemPromptBuilder systemPromptBuilder;
    private final ToolPermissionService toolPermissionService;
    private final ToolHookChain toolHookChain;
    private final ToolRunner toolRunner;

    public AgentSessionServices(
            AgentSessionConfig config,
            ModelRegistry modelRegistry,
            ToolRegistry toolRegistry,
            LlmClient llmClient
    ) {
        this(
                config,
                modelRegistry,
                toolRegistry,
                llmClient,
                config == null ? null : config.getTranscriptStore()
        );
    }

    public AgentSessionServices(
            AgentSessionConfig config,
            ModelRegistry modelRegistry,
            ToolRegistry toolRegistry,
            LlmClient llmClient,
            TranscriptStore transcriptStore
    ) {
        this(
                config,
                modelRegistry,
                toolRegistry,
                llmClient,
                transcriptStore,
                new ToolPoolCompiler(),
                new SystemPromptBuilder(),
                new DefaultToolPermissionService(),
                ToolHookChain.empty()
        );
    }

    public AgentSessionServices(
            AgentSessionConfig config,
            ModelRegistry modelRegistry,
            ToolRegistry toolRegistry,
            LlmClient llmClient,
            TranscriptStore transcriptStore,
            ToolPoolCompiler toolPoolCompiler,
            SystemPromptBuilder systemPromptBuilder
    ) {
        this(
                config,
                modelRegistry,
                toolRegistry,
                llmClient,
                transcriptStore,
                toolPoolCompiler,
                systemPromptBuilder,
                new DefaultToolPermissionService(),
                ToolHookChain.empty()
        );
    }

    public AgentSessionServices(
            AgentSessionConfig config,
            ModelRegistry modelRegistry,
            ToolRegistry toolRegistry,
            LlmClient llmClient,
            TranscriptStore transcriptStore,
            ToolPoolCompiler toolPoolCompiler,
            SystemPromptBuilder systemPromptBuilder,
            ToolPermissionService toolPermissionService,
            ToolHookChain toolHookChain
    ) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (modelRegistry == null) {
            throw new IllegalArgumentException("modelRegistry must not be null");
        }
        if (toolRegistry == null) {
            throw new IllegalArgumentException("toolRegistry must not be null");
        }
        if (llmClient == null) {
            throw new IllegalArgumentException("llmClient must not be null");
        }
        if (toolPoolCompiler == null) {
            throw new IllegalArgumentException("toolPoolCompiler must not be null");
        }
        if (systemPromptBuilder == null) {
            throw new IllegalArgumentException("systemPromptBuilder must not be null");
        }
        if (toolPermissionService == null) {
            throw new IllegalArgumentException("toolPermissionService must not be null");
        }
        if (toolHookChain == null) {
            throw new IllegalArgumentException("toolHookChain must not be null");
        }
        this.config = config;
        this.modelRegistry = modelRegistry;
        this.toolRegistry = toolRegistry;
        this.llmClient = llmClient;
        this.transcriptStore = transcriptStore;
        this.toolPoolCompiler = toolPoolCompiler;
        this.systemPromptBuilder = systemPromptBuilder;
        this.toolPermissionService = toolPermissionService;
        this.toolHookChain = toolHookChain;
        this.toolRunner = new ToolRunner(toolRegistry, toolPermissionService, toolHookChain);
    }
}
