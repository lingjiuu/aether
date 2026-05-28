package io.github.lingjiuu.tool.result;

import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.Tool;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ToolResultProcessorTest extends TestCase {

    public void testLargeToolResultPersistsAndReplacesModelVisibleText() throws Exception {
        Path root = Files.createTempDirectory("aether-tool-result-processor-test");
        ToolResultProcessor processor = processor(root);
        String large = "x".repeat(60_000);

        ToolResultMessage message = processor.processBatch(List.of(input(
                "call-1",
                tool("grep", ToolResultPolicy.withMaxResultSizeChars(20_000)),
                ToolExecutionResult.text(large)
        ))).getFirst();

        String text = MessageContents.text(message);
        assertTrue(text.startsWith(ToolResultProcessor.PERSISTED_OUTPUT_TAG));
        assertFalse(text.contains(large));
        assertTrue(text.contains("Full output saved to:"));
        Path artifact = onlyArtifact(root);
        assertEquals(large, Files.readString(artifact, StandardCharsets.UTF_8));
    }

    public void testReadPolicyNeverPersists() throws Exception {
        Path root = Files.createTempDirectory("aether-tool-result-processor-test");
        ToolResultProcessor processor = processor(root);
        String large = "r".repeat(60_000);

        ToolResultMessage message = processor.processBatch(List.of(input(
                "call-read",
                tool("read", ToolResultPolicy.neverPersist()),
                ToolExecutionResult.text(large)
        ))).getFirst();

        assertEquals(large, MessageContents.text(message));
        assertEquals(0, artifactCount(root));
    }

    public void testAggregateBudgetPersistsLargestInlineResults() throws Exception {
        Path root = Files.createTempDirectory("aether-tool-result-processor-test");
        ToolResultProcessor processor = processor(root);
        Tool tool = tool("ls", ToolResultPolicy.defaultPolicy());

        List<ToolResultMessage> messages = processor.processBatch(List.of(
                input("call-1", tool, ToolExecutionResult.text("a".repeat(45_000))),
                input("call-2", tool, ToolExecutionResult.text("b".repeat(45_000))),
                input("call-3", tool, ToolExecutionResult.text("c".repeat(45_000))),
                input("call-4", tool, ToolExecutionResult.text("d".repeat(45_000))),
                input("call-5", tool, ToolExecutionResult.text("e".repeat(45_000)))
        ));

        int persisted = 0;
        long modelVisibleChars = 0;
        for (ToolResultMessage message : messages) {
            String text = MessageContents.text(message);
            modelVisibleChars += text.length();
            if (text.startsWith(ToolResultProcessor.PERSISTED_OUTPUT_TAG)) {
                persisted++;
            }
        }
        assertTrue("expected at least one aggregate replacement", persisted >= 1);
        assertTrue("model-visible chars should be under aggregate budget: " + modelVisibleChars,
                modelVisibleChars <= ToolResultLimits.MAX_TOOL_RESULTS_PER_BATCH_CHARS);
    }

    public void testDetailsAreSanitizedToArtifactReference() throws Exception {
        Path root = Files.createTempDirectory("aether-tool-result-processor-test");
        ToolResultProcessor processor = processor(root);
        String diff = "diff".repeat(20_000);

        ToolResultMessage message = processor.processBatch(List.of(input(
                "call-detail",
                tool("edit", ToolResultPolicy.withMaxResultSizeChars(100_000)),
                ToolExecutionResult.builder()
                        .contents(ToolExecutionResult.text("updated").getContents())
                        .details(Map.of("kind", "edit", "diffText", diff))
                        .error(false)
                        .build()
        ))).getFirst();

        assertTrue(message.getDetails() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) message.getDetails();
        assertTrue(details.get("diffText") instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> diffRef = (Map<String, Object>) details.get("diffText");
        assertEquals(Boolean.TRUE, diffRef.get("persisted"));
        assertEquals(diff, Files.readString(Path.of(String.valueOf(diffRef.get("path"))), StandardCharsets.UTF_8));
    }

    public void testBashSourceFileIsPersistedAsMainOutput() throws Exception {
        Path root = Files.createTempDirectory("aether-tool-result-processor-test");
        Path source = root.resolve("bash-full.log");
        String fullOutput = "full bash output\n" + "z".repeat(60_000);
        Files.writeString(source, fullOutput, StandardCharsets.UTF_8);
        ToolResultProcessor processor = processor(root);

        ToolResultMessage message = processor.processBatch(List.of(input(
                "call-bash",
                tool("bash", ToolResultPolicy.withMaxResultSizeChars(30_000)),
                ToolExecutionResult.builder()
                        .contents(ToolExecutionResult.text("tail".repeat(20_000)).getContents())
                        .details(Map.of("kind", "bash", "aggregateFullOutputPath", source.toString()))
                        .error(false)
                        .build()
        ))).getFirst();

        assertTrue(MessageContents.text(message).startsWith(ToolResultProcessor.PERSISTED_OUTPUT_TAG));
        Path artifact = onlyArtifact(root);
        assertEquals(fullOutput, Files.readString(artifact, StandardCharsets.UTF_8));
    }

    private ToolResultProcessor processor(Path root) {
        return new ToolResultProcessor(null, new ToolArtifactStore(root.resolve("tool-results")));
    }

    private ToolResultInput input(String toolCallId, Tool tool, ToolExecutionResult result) {
        return new ToolResultInput(toolCall(toolCallId, tool.name()), tool, result);
    }

    private ToolCallContent toolCall(String id, String name) {
        return ToolCallContent.builder()
                .toolCallId(id)
                .toolName(name)
                .argumentsJson("{}")
                .build();
    }

    private Tool tool(String name, ToolResultPolicy policy) {
        return new FakeTool(name, policy);
    }

    private Path onlyArtifact(Path root) throws Exception {
        try (var paths = Files.walk(root.resolve("tool-results"))) {
            return paths.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
    }

    private long artifactCount(Path root) throws Exception {
        Path dir = root.resolve("tool-results");
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static final class FakeTool implements Tool {
        private final String name;
        private final ToolResultPolicy policy;

        private FakeTool(String name, ToolResultPolicy policy) {
            this.name = name;
            this.policy = policy;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String label() {
            return name;
        }

        @Override
        public String description() {
            return name;
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
        public ToolResultPolicy resultPolicy() {
            return policy;
        }

        @Override
        public ToolExecutionResult execute(io.github.lingjiuu.tool.ToolInvocation invocation) {
            return ToolExecutionResult.text("");
        }
    }
}
