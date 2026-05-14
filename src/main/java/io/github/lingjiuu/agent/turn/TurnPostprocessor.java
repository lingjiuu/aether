package io.github.lingjiuu.agent.turn;

import io.github.lingjiuu.agent.AgentEvent;
import io.github.lingjiuu.agent.invocation.ModelInvocationResult;
import io.github.lingjiuu.agent.runtime.AgentRunOptions;
import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolExecutionMode;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.ToolRunOptions;
import io.github.lingjiuu.tool.ToolRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

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
        return process(invocationResult, currentTurn, AgentRunOptions.defaults());
    }

    public TurnResult process(ModelInvocationResult invocationResult, int currentTurn, AgentRunOptions options) {
        if (invocationResult == null) {
            throw new IllegalArgumentException("invocationResult must not be null");
        }
        AgentRunOptions safeOptions = options == null ? AgentRunOptions.defaults() : options;

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
                currentTurn,
                safeOptions
        );
        appendedMessages.addAll(toolCallStep.toolResults());
        events.addAll(toolCallStep.events());

        return TurnResult.nextTurn(appendedMessages, events);
    }

    private ToolCallStep executeToolCalls(
            AssistantMessage assistantMessage,
            List<ToolCallContent> toolCalls,
            int turn,
            AgentRunOptions options
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
        }

        List<ToolCallExecution> executions = shouldRunSequentially(toolCalls)
                ? executeSequentially(assistantMessage, toolCalls, turn, options)
                : executeInParallel(assistantMessage, toolCalls, turn, options);
        for (ToolCallExecution execution : executions) {
            toolResults.add(execution.result());
            events.addAll(execution.updateEvents());
            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.TOOL_EXECUTION_END)
                    .turn(turn)
                    .toolCall(execution.toolCall())
                    .toolResult(execution.result())
                    .text(MessageContents.text(execution.result()))
                    .build());
            events.add(AgentEvent.builder()
                    .type(AgentEvent.Type.TOOL_RESULT)
                    .turn(turn)
                    .toolResult(execution.result())
                    .text(MessageContents.text(execution.result()))
                    .build());
        }

        return new ToolCallStep(toolResults, events);
    }

    private boolean shouldRunSequentially(List<ToolCallContent> toolCalls) {
        for (ToolCallContent toolCall : toolCalls) {
            if (toolRunner.executionModeFor(toolCall.getToolName()) == ToolExecutionMode.SEQUENTIAL) {
                return true;
            }
        }
        return false;
    }

    private List<ToolCallExecution> executeSequentially(
            AssistantMessage assistantMessage,
            List<ToolCallContent> toolCalls,
            int turn,
            AgentRunOptions options
    ) {
        List<ToolCallExecution> executions = new ArrayList<>();
        for (ToolCallContent toolCall : toolCalls) {
            executions.add(executeOne(assistantMessage, toolCall, turn, options));
        }
        return executions;
    }

    private List<ToolCallExecution> executeInParallel(
            AssistantMessage assistantMessage,
            List<ToolCallContent> toolCalls,
            int turn,
            AgentRunOptions options
    ) {
        List<CompletableFuture<ToolCallExecution>> futures = new ArrayList<>();
        for (ToolCallContent toolCall : toolCalls) {
            futures.add(CompletableFuture.supplyAsync(() -> executeOne(assistantMessage, toolCall, turn, options)));
        }
        List<ToolCallExecution> executions = new ArrayList<>();
        for (CompletableFuture<ToolCallExecution> future : futures) {
            executions.add(future.join());
        }
        return executions;
    }

    private ToolCallExecution executeOne(
            AssistantMessage assistantMessage,
            ToolCallContent toolCall,
            int turn,
            AgentRunOptions options
    ) {
        List<AgentEvent> updateEvents = new CopyOnWriteArrayList<>();
        ToolResultMessage result = toolRunner.run(
                assistantMessage,
                toolCall,
                partialResult -> updateEvents.add(AgentEvent.builder()
                        .type(AgentEvent.Type.TOOL_EXECUTION_UPDATE)
                        .turn(turn)
                        .toolCall(toolCall)
                        .partialToolResult(partialResult)
                        .build()),
                ToolRunOptions.withCancellationToken(options.cancellationToken())
        );
        return new ToolCallExecution(toolCall, result, List.copyOf(updateEvents));
    }

    private record ToolCallExecution(
            ToolCallContent toolCall,
            ToolResultMessage result,
            List<AgentEvent> updateEvents
    ) {
    }

    private record ToolCallStep(
            List<ToolResultMessage> toolResults,
            List<AgentEvent> events
    ) {
    }
}
