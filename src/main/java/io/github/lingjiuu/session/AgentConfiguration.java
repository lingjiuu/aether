package io.github.lingjiuu.session;

import io.github.lingjiuu.ai.AiModel;
import io.github.lingjiuu.ai.AiStreams;
import io.github.lingjiuu.ai.ModelRegistry;
import io.github.lingjiuu.auth.AuthStorage;
import io.github.lingjiuu.model.AgentTool;
import io.github.lingjiuu.model.Reasoning;
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
public class AgentConfiguration {

    private AuthStorage authStorage;

    private ModelRegistry modelRegistry;

    private AiStreams aiStreams;

    private String systemPrompt;

    private AiModel model;

    private Reasoning reasoning;

    @Builder.Default
    private List<AgentTool> defaultTools = new ArrayList<>();
}
