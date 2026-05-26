package io.github.lingjiuu.session;

import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.ReasoningOptions;
import io.github.lingjiuu.llm.ModelSelection;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.transcript.TranscriptStore;

import java.nio.file.Path;
import java.util.List;

public record SessionConfig(
        LlmClient llmClient,
        String baseInstructions,
        String developerInstructions,
        String userInstructions,
        List<Path> instructionSources,
        Path cwd,
        LlmModel model,
        RequestAuth requestAuth,
        ReasoningOptions reasoning,
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

    public ModelSelection modelSelection() {
        return new ModelSelection(model, requestAuth, reasoning);
    }

    public SessionConfig withModelSelection(ModelSelection selection) {
        if (selection == null) {
            throw new IllegalArgumentException("model selection must not be null");
        }
        return new SessionConfig(
                llmClient,
                baseInstructions,
                developerInstructions,
                userInstructions,
                instructionSources,
                cwd,
                selection.model(),
                selection.auth(),
                selection.reasoning(),
                transcriptStore,
                toolDefinitions,
                activeToolNames
        );
    }
}
