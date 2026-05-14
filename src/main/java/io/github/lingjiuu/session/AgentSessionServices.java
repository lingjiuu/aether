package io.github.lingjiuu.session;

import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.tool.ActiveToolSet;
import io.github.lingjiuu.tool.ActiveToolSetCompiler;
import io.github.lingjiuu.tool.ToolRunner;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.hook.ToolHookChain;
import io.github.lingjiuu.tool.permission.DefaultToolPermissionService;
import io.github.lingjiuu.tool.permission.ToolPermissionService;
import io.github.lingjiuu.transcript.TranscriptStore;
import lombok.Getter;

import java.util.List;

@Getter
public class AgentSessionServices {

    private final AgentSessionConfig config;
    private final ModelRegistry modelRegistry;
    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;
    private final TranscriptStore transcriptStore;
    private final ActiveToolSetCompiler activeToolSetCompiler;
    private final SystemPromptBuilder systemPromptBuilder;
    private final ToolPermissionService toolPermissionService;
    private final ToolHookChain toolHookChain;
    private final ToolRunner toolRunner;
    private List<String> activeToolNames;

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
                new ActiveToolSetCompiler(),
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
            ActiveToolSetCompiler activeToolSetCompiler,
            SystemPromptBuilder systemPromptBuilder
    ) {
        this(
                config,
                modelRegistry,
                toolRegistry,
                llmClient,
                transcriptStore,
                activeToolSetCompiler,
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
            ActiveToolSetCompiler activeToolSetCompiler,
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
        if (activeToolSetCompiler == null) {
            throw new IllegalArgumentException("activeToolSetCompiler must not be null");
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
        this.activeToolSetCompiler = activeToolSetCompiler;
        this.systemPromptBuilder = systemPromptBuilder;
        this.toolPermissionService = toolPermissionService;
        this.toolHookChain = toolHookChain;
        this.activeToolNames = config.resolvedActiveToolNames();
        this.toolRunner = new ToolRunner(this::activeToolSet, toolPermissionService, toolHookChain);
    }

    public synchronized ActiveToolSet activeToolSet() {
        return activeToolSetCompiler.compile(toolRegistry, activeToolNames);
    }

    public synchronized List<String> activeToolNames() {
        return activeToolNames == null ? null : List.copyOf(activeToolNames);
    }

    public synchronized void setActiveToolNames(List<String> activeToolNames) {
        this.activeToolNames = activeToolNames == null ? null : List.copyOf(activeToolNames);
    }
}
