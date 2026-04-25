package io.github.lingjiuu.agent.turn;

import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import io.github.lingjiuu.llm.LlmCallOptions;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.session.AgentSessionServices;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolPoolSnapshot;

import java.util.List;

public class TurnPreprocessor {

    private final AgentSessionServices services;

    public TurnPreprocessor(AgentSessionServices services) {
        if (services == null) {
            throw new IllegalArgumentException("services must not be null");
        }
        this.services = services;
    }

    public LlmRequest prepare(AgentRuntimeState runtimeState) {
        if (runtimeState == null) {
            throw new IllegalArgumentException("runtimeState must not be null");
        }

        List<Message> messagesForModel = runtimeState.snapshot();
        ToolPoolSnapshot toolPoolSnapshot = services.getToolPoolCompiler().compile(services.getToolRegistry());
        List<ToolDefinition> visibleTools = toolPoolSnapshot.visibleTools();
        String systemPrompt = services.getSystemPromptBuilder().build(
                services.getConfig().getSystemPrompt(),
                visibleTools
        );

        return LlmRequest.builder()
                .systemPrompt(systemPrompt)
                .model(services.getConfig().getModel())
                .tools(visibleTools)
                .messages(messagesForModel)
                .callOptions(LlmCallOptions.builder()
                        .reasoning(services.getConfig().getReasoning())
                        .build())
                .build();
    }
}
