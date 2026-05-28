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
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolInvocation;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.permission.PermissionPreset;
import io.github.lingjiuu.trace.sqlite.SqliteTraceStore;
import io.github.lingjiuu.transcript.TranscriptStore;
import io.github.lingjiuu.wire.WireAdapter;
import io.github.lingjiuu.wire.WireAdapterRegistry;
import io.github.lingjiuu.wire.WireSession;
import junit.framework.TestCase;

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
                && span.outputJson().contains("artifact-1")
                && span.outputJson().contains("\"truncated\":true")));
        assertTrue(detail.events().stream().anyMatch(event -> "conversation.tool_result".equals(event.type())
                && event.payloadJson() != null
                && event.payloadJson().contains("transcriptRecordId")
                && event.payloadJson().contains("artifact-1")));
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

    private static final class ArtifactTool implements Tool {

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
            return "Returns a model-visible result with future artifact metadata.";
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public ToolRiskLevel riskLevel() {
            return ToolRiskLevel.READ_ONLY;
        }

        @Override
        public ToolExecutionResult execute(ToolInvocation invocation) {
            return ToolExecutionResult.builder()
                    .contents(ToolExecutionResult.text("model-visible truncated output").getContents())
                    .details(Map.of(
                            "truncated", true,
                            "artifactId", "artifact-1",
                            "artifactPath", "/tmp/full-tool-output.txt"
                    ))
                    .error(false)
                    .build();
        }
    }
}
