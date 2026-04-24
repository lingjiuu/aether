package io.github.lingjiuu.session;

import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.transcript.TranscriptStore;
import lombok.Getter;

@Getter
public class AgentSessionServices {

    private final AgentSessionConfig config;
    private final ModelRegistry modelRegistry;
    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;
    private final TranscriptStore transcriptStore;

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
        this.config = config;
        this.modelRegistry = modelRegistry;
        this.toolRegistry = toolRegistry;
        this.llmClient = llmClient;
        this.transcriptStore = transcriptStore;
    }
}
