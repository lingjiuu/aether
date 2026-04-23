package io.github.lingjiuu.agent;

import io.github.lingjiuu.llm.LlmCallOptions;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.model.AgentConfig;

import java.util.List;

public class TurnPreprocessor {

    private final AgentConfig config;
    private final ContextTransformer contextTransformer;
    private final LlmMessageConverter llmMessageConverter;

    public TurnPreprocessor(
            AgentConfig config,
            ContextTransformer contextTransformer,
            LlmMessageConverter llmMessageConverter
    ) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (contextTransformer == null) {
            throw new IllegalArgumentException("contextTransformer must not be null");
        }
        if (llmMessageConverter == null) {
            throw new IllegalArgumentException("llmMessageConverter must not be null");
        }
        this.config = config;
        this.contextTransformer = contextTransformer;
        this.llmMessageConverter = llmMessageConverter;
    }

    public LlmRequest prepare(AgentRuntimeState runtimeState) {
        if (runtimeState == null) {
            throw new IllegalArgumentException("runtimeState must not be null");
        }

        List<Message> messagesForModel = contextTransformer.transformContext(runtimeState.snapshot());
        List<Message> llmMessages = llmMessageConverter.convertToLlm(messagesForModel);

        return LlmRequest.builder()
                .systemPrompt(config.getSystemPrompt())
                .model(config.getModel())
                .tools(config.getTools())
                .messages(llmMessages)
                .callOptions(LlmCallOptions.builder()
                        .reasoning(config.getReasoning())
                        .build())
                .build();
    }
}
