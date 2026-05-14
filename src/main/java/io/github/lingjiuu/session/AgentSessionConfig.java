package io.github.lingjiuu.session;

import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.ReasoningOptions;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.transcript.TranscriptStore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSessionConfig {

    private AuthStorage authStorage;

    private ModelRegistry modelRegistry;

    private LlmClient llmClient;

    private String systemPrompt;

    private LlmModel model;

    private ReasoningOptions reasoning;

    private TranscriptStore transcriptStore;

    @Builder.Default
    private List<ToolDefinition> toolDefinitions = new ArrayList<>();

    @Builder.Default
    private List<ToolDefinition> defaultTools = new ArrayList<>();

    private List<String> activeToolNames;

    public List<ToolDefinition> resolvedToolDefinitions() {
        if (toolDefinitions != null && !toolDefinitions.isEmpty()) {
            return List.copyOf(toolDefinitions);
        }
        if (defaultTools != null && !defaultTools.isEmpty()) {
            return List.copyOf(defaultTools);
        }
        return List.of();
    }

    public List<String> resolvedActiveToolNames() {
        return activeToolNames == null ? null : List.copyOf(activeToolNames);
    }
}
