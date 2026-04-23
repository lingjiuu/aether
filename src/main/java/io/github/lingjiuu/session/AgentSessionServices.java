package io.github.lingjiuu.session;

import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.tool.ToolRegistry;
import lombok.Getter;

@Getter
public class AgentSessionServices {

    private final AgentSessionConfig config;
    private final ModelRegistry modelRegistry;
    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;

    public AgentSessionServices(
            AgentSessionConfig config,
            ModelRegistry modelRegistry,
            ToolRegistry toolRegistry,
            LlmClient llmClient
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
    }
}
