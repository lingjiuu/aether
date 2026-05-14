package io.github.lingjiuu.agent.turn;

import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import io.github.lingjiuu.agent.runtime.RuntimeStatePatch;
import io.github.lingjiuu.agent.turn.pipeline.DefaultPreModelPipeline;
import io.github.lingjiuu.agent.turn.pipeline.PreModelContext;
import io.github.lingjiuu.agent.turn.pipeline.PreModelPipeline;
import io.github.lingjiuu.agent.turn.pipeline.PreModelPipelineResult;
import io.github.lingjiuu.llm.LlmCallOptions;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.session.AgentSessionServices;
import io.github.lingjiuu.tool.ActiveToolSet;
import io.github.lingjiuu.tool.ToolDefinition;

import java.util.List;

public class TurnPreprocessor {

    private final AgentSessionServices services;
    private final PreModelPipeline preModelPipeline;

    public TurnPreprocessor(AgentSessionServices services) {
        this(services, new DefaultPreModelPipeline());
    }

    public TurnPreprocessor(AgentSessionServices services, PreModelPipeline preModelPipeline) {
        if (services == null) {
            throw new IllegalArgumentException("services must not be null");
        }
        if (preModelPipeline == null) {
            throw new IllegalArgumentException("preModelPipeline must not be null");
        }
        this.services = services;
        this.preModelPipeline = preModelPipeline;
    }

    public LlmRequest prepare(AgentRuntimeState runtimeState) {
        return prepareTurn(runtimeState).request();
    }

    public PreparedTurn prepareTurn(AgentRuntimeState runtimeState) {
        if (runtimeState == null) {
            throw new IllegalArgumentException("runtimeState must not be null");
        }

        List<Message> originalMessages = runtimeState.snapshot();
        PreModelPipelineResult pipelineResult = preModelPipeline.apply(
                new PreModelContext(services, runtimeState),
                originalMessages
        );
        List<Message> messagesForModel = pipelineResult.messages();
        ActiveToolSet activeToolSet = services.activeToolSet();
        List<ToolDefinition> activeTools = activeToolSet.activeTools();
        String systemPrompt = services.getSystemPromptBuilder().build(
                services.getConfig().getSystemPrompt(),
                activeTools
        );

        LlmRequest request = LlmRequest.builder()
                .systemPrompt(systemPrompt)
                .model(services.getConfig().getModel())
                .tools(activeTools)
                .messages(messagesForModel)
                .callOptions(LlmCallOptions.builder()
                        .reasoning(services.getConfig().getReasoning())
                        .build())
                .build();
        RuntimeStatePatch patch = originalMessages.equals(messagesForModel)
                ? RuntimeStatePatch.none()
                : RuntimeStatePatch.replaceActiveMessages(messagesForModel, pipelineResult.recordedMessages());
        return new PreparedTurn(request, patch);
    }
}
