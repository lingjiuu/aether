package io.github.lingjiuu.session;

import io.github.lingjiuu.model.client.ModelClient;
import io.github.lingjiuu.model.ModelInfo;
import io.github.lingjiuu.model.ReasoningOptions;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.provider.ProviderAuth;
import io.github.lingjiuu.provider.ProviderEndpoint;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.transcript.TranscriptStore;

import java.nio.file.Path;
import java.util.List;

public record SessionConfig(
        ModelClient modelClient,
        String baseInstructions,
        String developerInstructions,
        String userInstructions,
        List<Path> instructionSources,
        Path cwd,
        ModelSelection modelSelection,
        TranscriptStore transcriptStore,
        List<ToolDefinition> toolDefinitions,
        List<String> activeToolNames
) {

    public SessionConfig {
        baseInstructions = baseInstructions == null ? "" : baseInstructions;
        developerInstructions = developerInstructions == null ? "" : developerInstructions;
        userInstructions = userInstructions == null ? "" : userInstructions;
        instructionSources = instructionSources == null ? List.of() : List.copyOf(instructionSources);
        toolDefinitions = toolDefinitions == null ? List.of() : List.copyOf(toolDefinitions);
        activeToolNames = activeToolNames == null ? null : List.copyOf(activeToolNames);
    }

    public ModelInfo model() {
        return modelSelection == null ? null : modelSelection.model();
    }

    public ProviderEndpoint endpoint() {
        return modelSelection == null ? null : modelSelection.endpoint();
    }

    public ProviderAuth requestAuth() {
        return modelSelection == null ? null : modelSelection.auth();
    }

    public ReasoningOptions reasoning() {
        return modelSelection == null ? null : modelSelection.reasoning();
    }

    public SessionConfig withModelSelection(ModelSelection selection) {
        if (selection == null) {
            throw new IllegalArgumentException("model selection must not be null");
        }
        return new SessionConfig(
                modelClient,
                baseInstructions,
                developerInstructions,
                userInstructions,
                instructionSources,
                cwd,
                selection,
                transcriptStore,
                toolDefinitions,
                activeToolNames
        );
    }
}
