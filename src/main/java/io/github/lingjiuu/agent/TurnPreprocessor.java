package io.github.lingjiuu.agent;

import io.github.lingjiuu.llm.LlmCallOptions;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.session.AgentSessionServices;

import java.util.List;

public class TurnPreprocessor {

    private final AgentSessionServices services;
    private final ContextTransformer contextTransformer;
    private final LlmMessageConverter llmMessageConverter;

    public TurnPreprocessor(
            AgentSessionServices services,
            ContextTransformer contextTransformer,
            LlmMessageConverter llmMessageConverter
    ) {
        if (services == null) {
            throw new IllegalArgumentException("services must not be null");
        }
        if (contextTransformer == null) {
            throw new IllegalArgumentException("contextTransformer must not be null");
        }
        if (llmMessageConverter == null) {
            throw new IllegalArgumentException("llmMessageConverter must not be null");
        }
        this.services = services;
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
                .systemPrompt(services.getConfig().getSystemPrompt())
                .model(services.getConfig().getModel())
                .tools(services.getToolRegistry().definitions())
                .messages(llmMessages)
                .callOptions(LlmCallOptions.builder()
                        .reasoning(services.getConfig().getReasoning())
                        .build())
                .build();
    }
}
