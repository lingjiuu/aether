package io.github.lingjiuu.agent;

import io.github.lingjiuu.llm.LlmClient;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.llm.ReasoningOptions;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.provider.ProviderRegistry;
import io.github.lingjiuu.session.AgentSessionConfig;
import io.github.lingjiuu.session.AgentSessionServices;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.builtin.GetTimeTool;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class TurnPreprocessorTest extends TestCase {

    public void testPrepareBuildsDirectLlmRequestFromRuntimeState() throws Exception {
        AgentLoopTest.StubModelRegistry modelRegistry = new AgentLoopTest.StubModelRegistry();
        LlmClient llmClient = new LlmClient(
                modelRegistry,
                new ProviderRegistry().register(new AgentLoopTest.FakeProvider(List.of(), List.of(), new ArrayList<>()))
        );
        AgentSessionConfig config = AgentSessionConfig.builder()
                .modelRegistry(modelRegistry)
                .llmClient(llmClient)
                .systemPrompt("You are a helpful assistant")
                .model(LlmModel.builder()
                        .id("test-model")
                        .name("Test Model")
                        .api("fake")
                        .provider("fake")
                        .baseUrl("https://example.test/v1")
                        .build())
                .reasoning(ReasoningOptions.builder()
                        .reasoningEffort(ReasoningOptions.ReasoningEffort.HIGH)
                        .build())
                .build();
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new GetTimeTool());
        AgentSessionServices services = new AgentSessionServices(
                config,
                modelRegistry,
                toolRegistry,
                llmClient
        );

        AgentRuntimeState runtimeState = new AgentRuntimeState(List.of(
                UserMessage.builder()
                        .contents(List.of(TextContent.builder().text("Hello").build()))
                        .build()
        ));
        List<String> callOrder = new ArrayList<>();

        TurnPreprocessor preprocessor = new TurnPreprocessor(
                services,
                new AgentLoopTest.RecordingContextTransformer(callOrder),
                new AgentLoopTest.RecordingLlmMessageConverter(callOrder)
        );

        LlmRequest request = preprocessor.prepare(runtimeState);

        assertEquals("You are a helpful assistant", request.getSystemPrompt());
        assertEquals(config.getModel(), request.getModel());
        assertEquals(1, request.getTools().size());
        assertEquals("get_time", request.getTools().getFirst().name());
        assertEquals(1, request.getMessages().size());
        assertEquals("Hello", MessageContents.text(request.getMessages().getFirst()));
        assertNotNull(request.getCallOptions());
        assertNotNull(request.getCallOptions().getReasoning());
        assertEquals(
                ReasoningOptions.ReasoningEffort.HIGH,
                request.getCallOptions().getReasoning().getReasoningEffort()
        );
        assertEquals(List.of(
                "transformContext:1",
                "convertToLlm:1"
        ), callOrder);
    }
}
