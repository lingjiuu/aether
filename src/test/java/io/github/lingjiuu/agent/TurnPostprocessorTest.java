package io.github.lingjiuu.agent;

import io.github.lingjiuu.agent.invocation.ModelInvocationResult;
import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import io.github.lingjiuu.agent.turn.TurnPostprocessor;
import io.github.lingjiuu.agent.turn.TurnResult;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolRegistry;
import junit.framework.TestCase;

import java.util.List;

public class TurnPostprocessorTest extends TestCase {

    public void testProcessExecutesToolCallsAndReturnsNextTurnResult() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new AgentLoopTest.EchoTool());
        TurnPostprocessor postprocessor = new TurnPostprocessor(toolRegistry);

        ModelInvocationResult invocationResult = new ModelInvocationResult(
                AgentLoopTest.responseWithToolCall(),
                "Let me check that for you.",
                MessageContents.toolCalls(AgentLoopTest.responseWithToolCall()),
                List.of(AgentEvent.builder()
                        .type(AgentEvent.Type.ASSISTANT_TEXT_DELTA)
                        .turn(2)
                        .delta("Let me check that for you.")
                        .build())
        );

        TurnResult turnResult = postprocessor.process(invocationResult, 2);

        assertEquals(TurnResult.Transition.NEXT_TURN, turnResult.transition());
        assertNull(turnResult.terminationReason());
        assertEquals(2, turnResult.appendedMessages().size());
        assertEquals("Let me check that for you.", MessageContents.text(turnResult.appendedMessages().getFirst()));
        assertEquals("Echo: ping", MessageContents.text(turnResult.appendedMessages().get(1)));
        assertEquals(List.of(
                AgentEvent.Type.ASSISTANT_TEXT_DELTA,
                AgentEvent.Type.ASSISTANT_MESSAGE,
                AgentEvent.Type.TOOL_CALL,
                AgentEvent.Type.TOOL_EXECUTION_START,
                AgentEvent.Type.TOOL_EXECUTION_END,
                AgentEvent.Type.TOOL_RESULT
        ), turnResult.events().stream().map(AgentEvent::getType).toList());
    }

    public void testProcessReturnsCompletedResultForFinalAnswer() {
        TurnPostprocessor postprocessor = new TurnPostprocessor(new ToolRegistry());
        ModelInvocationResult invocationResult = new ModelInvocationResult(
                AgentLoopTest.finalResponse(),
                "Done.",
                List.of(),
                List.of(AgentEvent.builder()
                        .type(AgentEvent.Type.ASSISTANT_TEXT_DELTA)
                        .turn(1)
                        .delta("Done.")
                        .build())
        );

        TurnResult turnResult = postprocessor.process(invocationResult, 1);

        assertEquals(TurnResult.Transition.FINISH, turnResult.transition());
        assertEquals(AgentRuntimeState.TerminationReason.COMPLETED, turnResult.terminationReason());
        assertEquals(1, turnResult.appendedMessages().size());
        assertEquals(List.of(
                AgentEvent.Type.ASSISTANT_TEXT_DELTA,
                AgentEvent.Type.ASSISTANT_MESSAGE,
                AgentEvent.Type.FINAL_ANSWER
        ), turnResult.events().stream().map(AgentEvent::getType).toList());
    }

    public void testProcessClosesUnknownToolCallAsErrorResult() {
        TurnPostprocessor postprocessor = new TurnPostprocessor(new ToolRegistry());
        ToolCallContent missingToolCall = ToolCallContent.builder()
                .toolCallId("call-unknown")
                .toolName("missing_tool")
                .argumentsJson("{}")
                .build();
        ModelInvocationResult invocationResult = new ModelInvocationResult(
                AssistantMessage.builder()
                        .provider("fake")
                        .model("test-model")
                        .contents(List.of(
                                TextContent.builder().text("I will call a tool.").build(),
                                missingToolCall
                        ))
                        .build(),
                "I will call a tool.",
                List.of(missingToolCall),
                List.of()
        );

        TurnResult turnResult = postprocessor.process(invocationResult, 1);

        assertEquals(TurnResult.Transition.NEXT_TURN, turnResult.transition());
        assertEquals(2, turnResult.appendedMessages().size());
        assertTrue(((ToolResultMessage) turnResult.appendedMessages().get(1)).isError());
        assertTrue(MessageContents.text(turnResult.appendedMessages().get(1)).contains("Unsupported tool: missing_tool"));
    }
}
