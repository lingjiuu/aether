package io.github.lingjiuu.agent.turn;

import io.github.lingjiuu.agent.AgentEvent;
import io.github.lingjiuu.agent.invocation.ModelInvocationResult;
import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.ToolRunner;

import java.util.ArrayList;
import java.util.List;

public class TurnPostprocessor {

    private final ToolRunner toolRunner;

    public TurnPostprocessor(ToolRegistry toolRegistry) {
        this(new ToolRunner(toolRegistry));
    }

    public TurnPostprocessor(ToolRunner toolRunner) {
        if (toolRunner == null) {
            throw new IllegalArgumentException("toolRunner must not be null");
        }
        this.toolRunner = toolRunner;
    }

    public TurnResult process(ModelInvocationResult invocationResult, int currentTurn) {
        if (invocationResult == null) {
            throw new IllegalArgumentException("invocationResult must not be null");
        }

        List<Message> appendedMessages = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();

        AssistantMessage assistantMessage = invocationResult.assistantMessage();
        appendedMessages.add(assistantMessage);
        events.addAll(invocationResult.streamEvents());
        events.add(AgentEvent.builder()
                .type(AgentEvent.Type.ASSISTANT_MESSAGE)
                .turn(currentTurn)
                .assistantMessage(assistantMessage)
                .text(invocationResult.assistantText())
                .build());

        if (assistantMessage.getStopReason() == AssistantMessage.StopReason.ERROR) {
            return TurnResult.finish(
                    appendedMessages,
                    events,
                    AgentRuntimeState.TerminationReason.FAILED
            );
        }
        if (assistantMessage.getStopReason() == AssistantMessage.StopReason.ABORTED) {
            return TurnResult.finish(
                    appendedMessages,
                    events,
                    AgentRuntimeState.TerminationReason.ABORTED
            );
        }

        if (invocationResult.toolCalls().isEmpty()) {
            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.FINAL_ANSWER)
                    .turn(currentTurn)
                    .assistantMessage(assistantMessage)
                    .text(invocationResult.assistantText())
                    .build());
            return TurnResult.finish(
                    appendedMessages,
                    events,
                    AgentRuntimeState.TerminationReason.COMPLETED
            );
        }

        ToolCallStep toolCallStep = executeToolCalls(
                assistantMessage,
                invocationResult.toolCalls(),
                currentTurn
        );
        appendedMessages.addAll(toolCallStep.toolResults());
        events.addAll(toolCallStep.events());

        return TurnResult.nextTurn(appendedMessages, events);
    }

    private ToolCallStep executeToolCalls(
            AssistantMessage assistantMessage,
            List<ToolCallContent> toolCalls,
            int turn
    ) {
        List<ToolResultMessage> toolResults = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();

        for (ToolCallContent toolCall : toolCalls) {
            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.TOOL_CALL)
                    .turn(turn)
                    .toolCall(toolCall)
                    .build());
            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.TOOL_EXECUTION_START)
                    .turn(turn)
                    .toolCall(toolCall)
                    .build());

            ToolResultMessage result = toolRunner.run(
                    assistantMessage,
                    toolCall,
                    partialResult -> events.add(AgentEvent.builder()
                            .type(AgentEvent.Type.TOOL_EXECUTION_UPDATE)
                            .turn(turn)
                            .toolCall(toolCall)
                            .partialToolResult(partialResult)
                            .build())
            );
            toolResults.add(result);

            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.TOOL_EXECUTION_END)
                    .turn(turn)
                    .toolCall(toolCall)
                    .toolResult(result)
                    .text(MessageContents.text(result))
                    .build());
            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.TOOL_RESULT)
                    .turn(turn)
                    .toolResult(result)
                    .text(MessageContents.text(result))
                    .build());
        }

        return new ToolCallStep(toolResults, events);
    }

    private record ToolCallStep(
            List<ToolResultMessage> toolResults,
            List<AgentEvent> events
    ) {
    }
}
