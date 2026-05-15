package io.github.lingjiuu.agent;

import io.github.lingjiuu.agent.runtime.AgentRuntimeState;
import io.github.lingjiuu.agent.turn.TurnPreprocessor;
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
import io.github.lingjiuu.tool.tools.GrepTool;
import io.github.lingjiuu.tool.tools.LsTool;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import junit.framework.TestCase;

import java.nio.file.Files;
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
                .activeToolNames(List.of("grep"))
                .reasoning(ReasoningOptions.builder()
                        .reasoningEffort(ReasoningOptions.ReasoningEffort.HIGH)
                        .build())
                .build();
        ToolRegistry toolRegistry = new ToolRegistry();
        WorkspaceAccessPolicy accessPolicy = WorkspaceAccessPolicy.rootedAt(Files.createTempDirectory("aether-active-tools"));
        toolRegistry.register(new GrepTool(accessPolicy));
        toolRegistry.register(new LsTool(accessPolicy));
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
        TurnPreprocessor preprocessor = new TurnPreprocessor(services);

        LlmRequest request = preprocessor.prepare(runtimeState);

        assertTrue(request.getSystemPrompt().startsWith("You are a helpful assistant"));
        assertTrue(request.getSystemPrompt().contains("- grep: Search file contents for patterns (respects .gitignore)"));
        assertTrue(request.getSystemPrompt().contains("Use grep to search file contents before opening broad areas of the project."));
        assertFalse(request.getSystemPrompt().contains("- ls:"));
        assertFalse(request.getSystemPrompt().contains("Use ls to inspect directory contents"));
        assertEquals(config.getModel(), request.getModel());
        assertEquals(1, request.getTools().size());
        assertEquals("grep", request.getTools().getFirst().name());
        assertEquals(1, request.getMessages().size());
        assertEquals("Hello", MessageContents.text(request.getMessages().getFirst()));
        assertNotNull(request.getCallOptions());
        assertNotNull(request.getCallOptions().getReasoning());
        assertEquals(
                ReasoningOptions.ReasoningEffort.HIGH,
                request.getCallOptions().getReasoning().getReasoningEffort()
        );
    }

    public void testPrepareWithEmptyActiveToolsSendsNoToolsOrToolPrompt() throws Exception {
        AgentLoopTest.StubModelRegistry modelRegistry = new AgentLoopTest.StubModelRegistry();
        LlmClient llmClient = new LlmClient(
                modelRegistry,
                new ProviderRegistry().register(new AgentLoopTest.FakeProvider(List.of(), List.of(), new ArrayList<>()))
        );
        AgentSessionConfig config = AgentSessionConfig.builder()
                .modelRegistry(modelRegistry)
                .llmClient(llmClient)
                .systemPrompt("Base")
                .model(LlmModel.builder()
                        .id("test-model")
                        .name("Test Model")
                        .api("fake")
                        .provider("fake")
                        .baseUrl("https://example.test/v1")
                        .build())
                .activeToolNames(List.of())
                .build();
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new GrepTool(WorkspaceAccessPolicy.rootedAt(Files.createTempDirectory("aether-active-tools"))));
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

        LlmRequest request = new TurnPreprocessor(services).prepare(runtimeState);

        assertEquals("Base", request.getSystemPrompt());
        assertTrue(request.getTools().isEmpty());
    }
}
