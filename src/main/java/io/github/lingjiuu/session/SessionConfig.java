package io.github.lingjiuu.session;

import io.github.lingjiuu.model.client.ModelClient;
import io.github.lingjiuu.model.ModelInfo;
import io.github.lingjiuu.model.ReasoningOptions;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.provider.ProviderAuth;
import io.github.lingjiuu.provider.ProviderEndpoint;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.permission.PermissionPreset;
import io.github.lingjiuu.trace.AgentTraceRecorder;
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
        AgentTraceRecorder traceRecorder,
        List<Tool> tools,
        List<String> activeToolNames,
        PermissionPreset permissionPreset
) {

    public SessionConfig(
            ModelClient modelClient,
            String baseInstructions,
            String developerInstructions,
            String userInstructions,
            List<Path> instructionSources,
            Path cwd,
            ModelSelection modelSelection,
            TranscriptStore transcriptStore,
            List<Tool> tools,
            List<String> activeToolNames
    ) {
        this(
                modelClient,
                baseInstructions,
                developerInstructions,
                userInstructions,
                instructionSources,
                cwd,
                modelSelection,
                transcriptStore,
                null,
                tools,
                activeToolNames,
                PermissionPreset.DEFAULT
        );
    }

    public SessionConfig(
            ModelClient modelClient,
            String baseInstructions,
            String developerInstructions,
            String userInstructions,
            List<Path> instructionSources,
            Path cwd,
            ModelSelection modelSelection,
            TranscriptStore transcriptStore,
            List<Tool> tools,
            List<String> activeToolNames,
            PermissionPreset permissionPreset
    ) {
        this(
                modelClient,
                baseInstructions,
                developerInstructions,
                userInstructions,
                instructionSources,
                cwd,
                modelSelection,
                transcriptStore,
                null,
                tools,
                activeToolNames,
                permissionPreset
        );
    }

    public SessionConfig {
        baseInstructions = baseInstructions == null ? "" : baseInstructions;
        developerInstructions = developerInstructions == null ? "" : developerInstructions;
        userInstructions = userInstructions == null ? "" : userInstructions;
        instructionSources = instructionSources == null ? List.of() : List.copyOf(instructionSources);
        traceRecorder = traceRecorder == null ? AgentTraceRecorder.noop() : traceRecorder;
        tools = tools == null ? List.of() : List.copyOf(tools);
        activeToolNames = activeToolNames == null ? null : List.copyOf(activeToolNames);
        permissionPreset = permissionPreset == null ? PermissionPreset.DEFAULT : permissionPreset;
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
                traceRecorder,
                tools,
                activeToolNames,
                permissionPreset
        );
    }

    public SessionConfig withPermissionPreset(PermissionPreset preset) {
        return new SessionConfig(
                modelClient,
                baseInstructions,
                developerInstructions,
                userInstructions,
                instructionSources,
                cwd,
                modelSelection,
                transcriptStore,
                traceRecorder,
                tools,
                activeToolNames,
                preset
        );
    }
}
