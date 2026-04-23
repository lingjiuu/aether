package io.github.lingjiuu.model;

import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.ReasoningOptions;
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

    private LlmModel model;

    private ReasoningOptions reasoning;

    @Builder.Default
    private List<AgentTool> tools = new ArrayList<>();
}
