package io.github.lingjiuu.tool.tools;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.runtime.ToolCancellationSource;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.runtime.ToolRunOptions;
import io.github.lingjiuu.tool.runtime.ToolRunner;
import io.github.lingjiuu.tool.workspace.WorkspaceAccessPolicy;
import io.github.lingjiuu.tool.render.ToolRenderRequest;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class BashToolTest extends TestCase {

    public void testSuccessfulCommandReturnsOutput() throws Exception {
        Path root = Files.createTempDirectory("aether-bash-root");

        ToolResultMessage result = execute(
                new BashTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"command\":\"printf hello\"}"
        );

        assertFalse(result.isError());
        assertEquals("hello", MessageContents.text(result));
    }

    public void testCommandRunsInWorkspaceRoot() throws Exception {
        Path root = Files.createTempDirectory("aether-bash-root");

        ToolResultMessage result = execute(
                new BashTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"command\":\"pwd\"}"
        );

        assertFalse(result.isError());
        assertEquals(WorkspaceAccessPolicy.rootedAt(root).root().toString(), MessageContents.text(result));
    }

    public void testNonZeroExitReturnsErrorWithOutputAndExitCode() throws Exception {
        Path root = Files.createTempDirectory("aether-bash-root");

        ToolResultMessage result = execute(
                new BashTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"command\":\"printf failure; exit 7\"}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("failure"));
        assertTrue(MessageContents.text(result).contains("Command exited with code 7"));
        assertEquals(7, ((Map<?, ?>) result.getDetails()).get("exitCode"));
    }

    public void testTimeoutKillsSlowCommand() throws Exception {
        Path root = Files.createTempDirectory("aether-bash-root");

        ToolResultMessage result = execute(
                new BashTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"command\":\"sleep 5\",\"timeout\":1}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("Command timed out after 1 seconds"));
    }

    public void testCancellationKillsSlowCommand() throws Exception {
        Path root = Files.createTempDirectory("aether-bash-root");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool(WorkspaceAccessPolicy.rootedAt(root)));
        ToolRunner runner = new ToolRunner(registry);
        ToolCancellationSource source = new ToolCancellationSource();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ToolResultMessage> future = executor.submit(() -> runner.run(
                    assistantMessage(),
                    toolCall("bash", "{\"command\":\"sleep 5\"}"),
                    null,
                    ToolRunOptions.withCancellationToken(source.token())
            ));
            Thread.sleep(200);
            source.cancel();
            ToolResultMessage result = future.get(2, TimeUnit.SECONDS);

            assertTrue(result.isError());
            assertTrue(MessageContents.text(result).contains("Tool execution cancelled."));
        } finally {
            executor.shutdownNow();
        }
    }

    public void testEmitsPartialUpdates() throws Exception {
        Path root = Files.createTempDirectory("aether-bash-root");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool(WorkspaceAccessPolicy.rootedAt(root)));
        ToolRunner runner = new ToolRunner(registry);
        List<ToolExecutionResult> updates = new ArrayList<>();

        ToolResultMessage result = runner.run(
                assistantMessage(),
                toolCall("bash", "{\"command\":\"printf alpha; sleep 0.2; printf beta\"}"),
                updates::add
        );

        assertFalse(result.isError());
        assertEquals("alphabeta", MessageContents.text(result));
        assertFalse(updates.isEmpty());
        assertTrue(MessageContents.text(toMessage(updates.getFirst())).contains("alpha"));
    }

    public void testRenderCallShowsCommandAndTimeout() {
        BashTool tool = new BashTool(Path.of("."));
        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName("bash")
                .argumentsJson("{}")
                .build();

        String rendered = tool.renderCall(ToolRenderRequest.forCall(
                toolCall,
                Map.of("command", "mvn test", "timeout", 10)
        )).text();

        assertEquals("$ mvn test (timeout 10s)", rendered);
    }

    public void testRejectsUnknownFieldThroughToolRunner() throws Exception {
        Path root = Files.createTempDirectory("aether-bash-root");

        ToolResultMessage result = execute(
                new BashTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"command\":\"printf hello\",\"extra\":true}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("Unknown tool argument: extra"));
    }

    public void testRejectsWrongCommandTypeThroughToolRunner() throws Exception {
        Path root = Files.createTempDirectory("aether-bash-root");

        ToolResultMessage result = execute(
                new BashTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"command\":42}"
        );

        assertTrue(result.isError());
        assertTrue(MessageContents.text(result).contains("command must be a string"));
    }

    public void testLargeOutputIsTailTruncatedAndFullOutputIsReadable() throws Exception {
        Path root = Files.createTempDirectory("aether-bash-root");
        String command = "for i in $(seq 1 2100); do echo line-$i; done";

        ToolResultMessage result = execute(
                new BashTool(WorkspaceAccessPolicy.rootedAt(root)),
                "{\"command\":\"" + command + "\"}"
        );

        assertFalse(result.isError());
        String text = MessageContents.text(result);
        assertFalse(text.contains("line-1\n"));
        assertTrue(text.contains("line-2100"));
        assertTrue(text.contains("Full output:"));
        Map<?, ?> details = (Map<?, ?>) result.getDetails();
        Path fullOutput = Path.of(String.valueOf(details.get("fullOutputPath")));
        String fullText = Files.readString(fullOutput);
        assertTrue(fullText.contains("line-1"));
        assertTrue(fullText.contains("line-2100"));
    }

    private ToolResultMessage execute(ToolDefinition tool, String argumentsJson) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return new ToolRunner(registry).run(assistantMessage(), toolCall(tool.name(), argumentsJson), null);
    }

    private static AssistantMessage assistantMessage() {
        return AssistantMessage.builder()
                .provider("fake")
                .model("test-model")
                .contents(List.of(ToolCallContent.builder()
                        .toolCallId("assistant-call")
                        .toolName("noop")
                        .argumentsJson("{}")
                        .build()))
                .build();
    }

    private static ToolCallContent toolCall(String toolName, String argumentsJson) {
        return ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName(toolName)
                .argumentsJson(argumentsJson)
                .build();
    }

    private static ToolResultMessage toMessage(ToolExecutionResult result) {
        return ToolResultMessage.builder()
                .toolCallId("partial")
                .toolName("bash")
                .contents(result.getContents())
                .details(result.getDetails())
                .isError(result.isError())
                .build();
    }
}
