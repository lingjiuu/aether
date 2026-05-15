package io.github.lingjiuu.agent;

import io.github.lingjiuu.agent.invocation.AssistantStreamEventMapper;
import io.github.lingjiuu.agent.invocation.ModelInvocationResult;
import io.github.lingjiuu.agent.invocation.ModelInvoker;
import io.github.lingjiuu.agent.runtime.AgentRunOptions;
import io.github.lingjiuu.llm.AssistantStreamEvent;
import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.tool.runtime.ToolCancellationSource;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class ModelInvokerTest extends TestCase {

    public void testInvokeReturnsAssistantMessageToolCallsAndMappedStreamEvents() throws Exception {
        AgentLoopTest.FakeProvider provider = new AgentLoopTest.FakeProvider(
                List.of(AgentLoopTest.responseWithToolCall()),
                List.of(List.of(
                        AssistantStreamEvent.builder()
                                .type(AssistantStreamEvent.Type.TEXT_DELTA)
                                .delta("Let me check that for you.")
                                .build(),
                        AssistantStreamEvent.builder()
                                .type(AssistantStreamEvent.Type.THINKING_DELTA)
                                .delta("Thinking")
                                .build()
                )),
                new ArrayList<>()
        );
        LlmClient llmClient = new LlmClient(
                new AgentLoopTest.StubModelRegistry(),
                new ProviderRegistry().register(provider)
        );
        ModelInvoker modelInvoker = new ModelInvoker(llmClient, new AssistantStreamEventMapper());

        ModelInvocationResult result = modelInvoker.invoke(LlmRequest.builder()
                .model(LlmModel.builder()
                        .id("test-model")
                        .name("Test Model")
                        .api("fake")
                        .provider("fake")
                        .baseUrl("https://example.test/v1")
                        .build())
                .messages(List.of())
                .build(), 3);

        assertEquals("Let me check that for you.", result.assistantText());
        assertEquals(1, result.toolCalls().size());
        assertEquals("echo_tool", result.toolCalls().getFirst().getToolName());
        assertEquals(List.of(
                AgentEvent.Type.ASSISTANT_TEXT_DELTA,
                AgentEvent.Type.REASONING_DELTA
        ), result.streamEvents().stream().map(AgentEvent::getType).toList());
        assertEquals(Integer.valueOf(3), result.streamEvents().getFirst().getTurn());
        assertEquals(1, provider.requestsSeen().size());
    }

    public void testInvokeReturnsAbortedWithoutCallingProviderWhenAlreadyCancelled() throws Exception {
        AgentLoopTest.FakeProvider provider = new AgentLoopTest.FakeProvider(
                List.of(AgentLoopTest.finalResponse()),
                List.of(List.of()),
                new ArrayList<>()
        );
        LlmClient llmClient = new LlmClient(
                new AgentLoopTest.StubModelRegistry(),
                new ProviderRegistry().register(provider)
        );
        ModelInvoker modelInvoker = new ModelInvoker(llmClient, new AssistantStreamEventMapper());
        ToolCancellationSource cancellationSource = new ToolCancellationSource();
        cancellationSource.cancel();

        ModelInvocationResult result = modelInvoker.invoke(LlmRequest.builder()
                .model(LlmModel.builder()
                        .id("test-model")
                        .name("Test Model")
                        .api("fake")
                        .provider("fake")
                        .baseUrl("https://example.test/v1")
                        .build())
                .messages(List.of())
                .build(), 3, AgentRunOptions.withCancellationToken(cancellationSource.token()));

        assertEquals(AssistantMessage.StopReason.ABORTED, result.assistantMessage().getStopReason());
        assertEquals(0, provider.requestsSeen().size());
    }
}
