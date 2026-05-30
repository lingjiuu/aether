package io.github.lingjiuu.trace;

import io.github.lingjiuu.TestModelSelections;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.ModelSelection;
import io.github.lingjiuu.model.client.AssistantStream;
import io.github.lingjiuu.model.client.AssistantStreamEvent;
import io.github.lingjiuu.model.client.ModelClient;
import io.github.lingjiuu.session.Session;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.session.SessionFactory;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolCallResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolUseContext;
import io.github.lingjiuu.tool.permission.PermissionPreset;
import io.github.lingjiuu.tool.result.ModelToolResult;
import io.github.lingjiuu.tool.result.ToolResultContext;
import io.github.lingjiuu.tool.result.ToolResultProcessor;
import io.github.lingjiuu.trace.sqlite.SqliteTraceStore;
import io.github.lingjiuu.transcript.TranscriptStore;
import io.github.lingjiuu.wire.WireAdapter;
import io.github.lingjiuu.wire.WireAdapterRegistry;
import io.github.lingjiuu.wire.WireSession;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class SessionTraceIntegrationTest extends TestCase {

    public void testRegularTurnRecordsRunModelToolEventsAndTranscriptReferences() throws Exception {
        Path root = Files.createTempDirectory("aether-session-trace-test");
        SqliteTraceStore store = new SqliteTraceStore(root.resolve("trace.sqlite"));
        AgentTraceRecorder recorder = new AgentTraceRecorder(store);
        Tool tool = new ArtifactTool();
        SessionConfig config = new SessionConfig(
                new ModelClient(new WireAdapterRegistry().register(new ToolThenTextProvider())),
                "You are a trace test agent.",
                "",
                "",
                List.of(),
                root.toAbsolutePath().normalize(),
                TestModelSelections.fakeSelection(),
                new TranscriptStore(root.resolve("transcripts")),
                recorder,
                List.of(tool),
                List.of(tool.name()),
                PermissionPreset.FULL_ACCESS
        );

        try (Session session = new SessionFactory(config).openSession()) {
            session.submitAsync(io.github.lingjiuu.input.TurnInput.ofText("run traced tool"));
            assertTrue(session.waitForIdle(Duration.ofSeconds(5)));
        }

        var runs = store.listRuns(10);
        assertEquals(1, runs.size());
        var detail = store.readRun(runs.getFirst().runId()).orElse(null);
        assertNotNull(detail);
        assertEquals("COMPLETED", detail.run().status());
        assertTrue(detail.spans().stream().anyMatch(span -> "model".equals(span.kind())));
        assertTrue(detail.spans().stream().anyMatch(span -> "tool".equals(span.kind())
                && span.outputJson() != null
                && span.outputJson().contains("\"status\":\"COMPLETED\"")
                && span.outputJson().contains("executionDurationMs")));
        assertTrue(detail.events().stream().anyMatch(event -> "conversation.tool_result".equals(event.type())
                && event.payloadJson() != null
                && event.payloadJson().contains("transcriptRecordId")
                && event.payloadJson().contains("approvalWaitMs")
                && event.payloadJson().contains("executionDurationMs")
                && event.payloadJson().contains("artifactRefs")
                && event.payloadJson().contains("tool_result_output")
                && event.payloadJson().contains(ToolResultProcessor.PERSISTED_OUTPUT_TAG)));
        assertEquals(1, detail.artifacts().size());
        assertEquals("tool_result_output", detail.artifacts().getFirst().kind());
        assertTrue(detail.artifacts().getFirst().bytes() > 50_000L);
        assertNotNull(detail.artifacts().getFirst().sha256());
        assertTrue(Files.exists(Path.of(detail.artifacts().getFirst().path())));
        assertTrue(Files.readString(Path.of(detail.artifacts().getFirst().path()), StandardCharsets.UTF_8)
                .contains("artifact-visible-output"));
        assertTrue(detail.events().stream().anyMatch(event -> "ui.TOOL_EXECUTION_BEGIN".equals(event.type())));
        assertTrue(detail.events().stream().anyMatch(event -> "ui.TOOL_RESULT".equals(event.type())));
        store.close();
    }

    private static final class ToolThenTextProvider implements WireAdapter {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WireSession openSession(ModelSelection selection) {
            return (request, cancellationToken) -> calls.incrementAndGet() == 1
                    ? new ToolCallStream()
                    : new TextStream();
        }
    }

    private static final class ToolCallStream extends AssistantStream {
        private AssistantMessage result;

        @Override
        public AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
            ToolCallContent toolCall = ToolCallContent.builder()
                    .toolCallId("call-1")
                    .toolName("artifact_tool")
                    .argumentsJson("{}")
                    .build();
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TOOLCALL_END)
                    .itemId("item-tool")
                    .contentIndex(0)
                    .toolName("artifact_tool")
                    .toolCall(toolCall)
                    .partial(AssistantMessage.builder()
                            .contents(List.of(toolCall))
                            .stopReason(AssistantMessage.StopReason.TOOLUSE)
                            .build())
                    .build());
            result = AssistantMessage.builder()
                    .contents(List.of(toolCall))
                    .stopReason(AssistantMessage.StopReason.TOOLUSE)
                    .build();
            return result;
        }

        @Override
        public AssistantMessage result() {
            return result;
        }
    }

    private static final class TextStream extends AssistantStream {
        private AssistantMessage result;

        @Override
        public AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
            consumer.accept(AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_END)
                    .itemId("item-text")
                    .contentIndex(0)
                    .content("done")
                    .partial(AssistantMessage.builder()
                            .contents(List.of(TextContent.builder().text("done").build()))
                            .stopReason(AssistantMessage.StopReason.STOP)
                            .build())
                    .build());
            result = AssistantMessage.builder()
                    .contents(List.of(TextContent.builder().text("done").build()))
                    .stopReason(AssistantMessage.StopReason.STOP)
                    .build();
            return result;
        }

        @Override
        public AssistantMessage result() {
            return result;
        }
    }

    private static final class ArtifactTool implements Tool<Object, String> {

        @Override
        public String name() {
            return "artifact_tool";
        }

        @Override
        public String label() {
            return "Artifact Tool";
        }

        @Override
        public String description() {
            return "Returns a large model-visible result that should be persisted.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public ToolRiskLevel riskLevel() {
            return ToolRiskLevel.READ_ONLY;
        }

        @Override
        public Object parseInput(String argumentsJson) {
            return new Object();
        }

        @Override
        public ToolCallResult<String> call(Object input, ToolUseContext context) {
            return ToolCallResult.success("artifact-visible-output\n" + "x".repeat(60_000));
        }

        @Override
        public ModelToolResult toModelResult(String output, ToolResultContext<Object, String> context) {
            return ModelToolResult.text(output);
        }
    }
}
