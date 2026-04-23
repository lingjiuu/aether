package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.AssistantStreamEventMapper;
import io.github.lingjiuu.agent.ContextTransformer;
import io.github.lingjiuu.agent.DefaultContextTransformer;
import io.github.lingjiuu.agent.DefaultLlmMessageConverter;
import io.github.lingjiuu.agent.LlmMessageConverter;
import io.github.lingjiuu.ai.AiModel;
import io.github.lingjiuu.ai.AssistantSampler;
import io.github.lingjiuu.ai.ModelRegistry;
import io.github.lingjiuu.auth.AuthStorage;
import io.github.lingjiuu.model.Reasoning;
import io.github.lingjiuu.tool.ToolDefinition;
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

    private AssistantSampler assistantSampler;

    private String systemPrompt;

    private AiModel model;

    private Reasoning reasoning;

    @Builder.Default
    private ContextTransformer contextTransformer = new DefaultContextTransformer();

    @Builder.Default
    private LlmMessageConverter llmMessageConverter = new DefaultLlmMessageConverter();

    @Builder.Default
    private AssistantStreamEventMapper assistantStreamEventMapper = new AssistantStreamEventMapper();

    @Builder.Default
    private List<ToolDefinition> defaultTools = new ArrayList<>();
}
