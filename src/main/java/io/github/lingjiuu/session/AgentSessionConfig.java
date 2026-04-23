package io.github.lingjiuu.session;

import io.github.lingjiuu.agent.AssistantStreamEventMapper;
import io.github.lingjiuu.agent.ContextTransformer;
import io.github.lingjiuu.agent.DefaultContextTransformer;
import io.github.lingjiuu.agent.DefaultLlmMessageConverter;
import io.github.lingjiuu.agent.LlmMessageConverter;
import io.github.lingjiuu.infra.auth.AuthStorage;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.ReasoningOptions;
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

    private LlmClient llmClient;

    private String systemPrompt;

    private LlmModel model;

    private ReasoningOptions reasoning;

    @Builder.Default
    private ContextTransformer contextTransformer = new DefaultContextTransformer();

    @Builder.Default
    private LlmMessageConverter llmMessageConverter = new DefaultLlmMessageConverter();

    @Builder.Default
    private AssistantStreamEventMapper assistantStreamEventMapper = new AssistantStreamEventMapper();

    @Builder.Default
    private List<ToolDefinition> defaultTools = new ArrayList<>();
}
