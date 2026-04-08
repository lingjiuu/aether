package io.github.lingjiuu.model;

import io.github.lingjiuu.ai.AiModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AgentConfig {

    private String systemPrompt;

    private AiModel model;

    private Reasoning reasoning;

    @Builder.Default
    private List<AgentTool> tools = new ArrayList<>();
}
