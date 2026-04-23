package io.github.lingjiuu.agent;

import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.session.AgentSessionConfig;
import io.github.lingjiuu.session.AgentSessionServices;
import io.github.lingjiuu.tool.ToolRegistry;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class AgentRuntimeTest extends TestCase {

    public void testRunOwnsMultiTurnEventOrderingAndStateGrowth() throws Exception {
        AgentRuntimeState runtimeState = new AgentRuntimeState(List.of(
                UserMessage.builder()
                        .contents(List.of(TextContent.builder().text("What time is it?").build()))
                        .build()
        ));

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new AgentLoopTest.EchoTool());
        List<String> callOrder = new ArrayList<>();
        AgentLoopTest.StubModelRegistry modelRegistry = new AgentLoopTest.StubModelRegistry();

        AgentLoopTest.FakeProvider provider = new AgentLoopTest.FakeProvider(
                List.of(
                        AgentLoopTest.responseWithToolCall(),
                        AgentLoopTest.finalResponse()
                ),
                List.of(
                        List.of(AssistantStreamEvent.builder()
                                .type(AssistantStreamEvent.Type.TEXT_DELTA)
                                .delta("Let me check that for you.")
                                .build()),
                        List.of(AssistantStreamEvent.builder()
                                .type(AssistantStreamEvent.Type.TEXT_DELTA)
                                .delta("Done.")
                                .build())
                ),
                callOrder
        );
        LlmClient llmClient = new LlmClient(
                modelRegistry,
                new ProviderRegistry().register(provider)
        );
        AgentSessionConfig config = AgentLoopTest.sessionConfig(modelRegistry, llmClient);
        AgentSessionServices services = new AgentSessionServices(
                config,
                modelRegistry,
                toolRegistry,
                llmClient
        );

        AgentLoop agentLoop = new AgentLoop(
                services,
                new AgentLoopTest.RecordingContextTransformer(callOrder),
                new AgentLoopTest.RecordingLlmMessageConverter(callOrder),
                new AssistantStreamEventMapper()
        );
        AgentRuntime runtime = new AgentRuntime(runtimeState, agentLoop);

        List<AgentEvent.Type> eventTypes = new ArrayList<>();
        List<String> runtimeSizesByEvent = new ArrayList<>();
        runtime.run(event -> {
            eventTypes.add(event.getType());
            runtimeSizesByEvent.add(event.getType() + ":" + runtime.state().size());
        });

        assertEquals(List.of(
                AgentEvent.Type.RUN_START,
                AgentEvent.Type.TURN_START,
                AgentEvent.Type.ASSISTANT_TEXT_DELTA,
                AgentEvent.Type.ASSISTANT_MESSAGE,
                AgentEvent.Type.TOOL_CALL,
                AgentEvent.Type.TOOL_EXECUTION_START,
                AgentEvent.Type.TOOL_EXECUTION_END,
                AgentEvent.Type.TOOL_RESULT,
                AgentEvent.Type.TURN_START,
                AgentEvent.Type.ASSISTANT_TEXT_DELTA,
                AgentEvent.Type.ASSISTANT_MESSAGE,
                AgentEvent.Type.FINAL_ANSWER,
                AgentEvent.Type.RUN_END
        ), eventTypes);
        assertEquals(List.of(
                "RUN_START:1",
                "TURN_START:1",
                "ASSISTANT_TEXT_DELTA:1",
                "ASSISTANT_MESSAGE:2",
                "TOOL_CALL:2",
                "TOOL_EXECUTION_START:2",
                "TOOL_EXECUTION_END:2",
                "TOOL_RESULT:3",
                "TURN_START:3",
                "ASSISTANT_TEXT_DELTA:3",
                "ASSISTANT_MESSAGE:4",
                "FINAL_ANSWER:4",
                "RUN_END:4"
        ), runtimeSizesByEvent);
        assertTrue(runtime.state().isTerminal());
        assertEquals(AgentRuntimeState.TerminationReason.COMPLETED, runtime.state().terminationReason());
        assertEquals(4, runtime.state().size());
        assertEquals(2, provider.requestsSeen().size());
        assertEquals(1, provider.requestsSeen().getFirst().getMessages().size());
        assertEquals(3, provider.requestsSeen().get(1).getMessages().size());
        assertEquals("Echo: ping", MessageContents.text(provider.requestsSeen().get(1).getMessages().get(2)));
        assertEquals("Done.", MessageContents.text(runtime.state().snapshot().get(3)));
        assertEquals(List.of(
                "transformContext:1",
                "convertToLlm:1",
                "provider.stream:1",
                "transformContext:3",
                "convertToLlm:3",
                "provider.stream:3"
        ), callOrder);
    }
}
