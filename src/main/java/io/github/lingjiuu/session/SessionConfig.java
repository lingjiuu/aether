package io.github.lingjiuu.session;

import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.ReasoningOptions;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.resource.PromptResources;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.transcript.TranscriptStore;

import java.nio.file.Path;
import java.util.List;

public record SessionConfig(
        LlmClient llmClient,
        String systemPrompt,
        Path cwd,
        LlmModel model,
        RequestAuth requestAuth,
        ReasoningOptions reasoning,
        TranscriptStore transcriptStore,
        List<ToolDefinition> toolDefinitions,
        PromptResources promptResources,
        List<String> activeToolNames
) {

    public SessionConfig {
        toolDefinitions = toolDefinitions == null ? List.of() : List.copyOf(toolDefinitions);
        promptResources = promptResources == null ? PromptResources.empty() : promptResources;
        activeToolNames = activeToolNames == null ? null : List.copyOf(activeToolNames);
    }

    public ModelSelection modelSelection() {
        return new ModelSelection(model, requestAuth, reasoning);
    }

    public SessionConfig withModelSelection(ModelSelection selection) {
        if (selection == null) {
            throw new IllegalArgumentException("model selection must not be null");
        }
        return new SessionConfig(
                llmClient,
                systemPrompt,
                cwd,
                selection.model(),
                selection.auth(),
                selection.reasoning(),
                transcriptStore,
                toolDefinitions,
                promptResources,
                activeToolNames
        );
    }
}
