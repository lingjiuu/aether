package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.result.ModelToolResult;
import io.github.lingjiuu.tool.result.ToolResultContext;
import junit.framework.TestCase;

import java.util.List;
import java.util.Map;

public class ToolTest extends TestCase {

    public void testReadOnlyToolsDefaultToParallelSafe() {
        assertTrue(tool(ToolRiskLevel.READ_ONLY).supportsParallelToolCalls());
    }

    public void testWriteAndExecToolsDefaultToNonParallel() {
        assertFalse(tool(ToolRiskLevel.WRITE).supportsParallelToolCalls());
        assertFalse(tool(ToolRiskLevel.EXEC).supportsParallelToolCalls());
        assertFalse(tool(ToolRiskLevel.UNKNOWN).supportsParallelToolCalls());
    }

    public void testRegistryRejectsDuplicateToolNames() {
        ToolRegistry registry = new ToolRegistry();

        registry.register(tool("read", ToolRiskLevel.READ_ONLY));

        IllegalArgumentException error = null;
        try {
            registry.register(tool("read", ToolRiskLevel.READ_ONLY));
        } catch (IllegalArgumentException e) {
            error = e;
        }
        assertNotNull(error);
        assertTrue(error.getMessage().contains("Tool already registered: read"));
    }

    public void testRegistryResolvesFoundInactiveAndUnsupportedTools() {
        ToolRegistry registry = new ToolRegistry();
        Tool read = tool("read", ToolRiskLevel.READ_ONLY);
        registry.register(read);
        registry.register(tool("write", ToolRiskLevel.WRITE));

        ToolRegistry.ToolResolution found = registry.resolve("read", List.of("read"));
        ToolRegistry.ToolResolution inactive = registry.resolve("write", List.of("read"));
        ToolRegistry.ToolResolution unsupported = registry.resolve("bash", List.of("read"));

        assertEquals(ToolRegistry.ToolResolutionStatus.FOUND, found.status());
        assertSame(read, found.tool());
        assertEquals(ToolRegistry.ToolResolutionStatus.INACTIVE, inactive.status());
        assertNull(inactive.tool());
        assertEquals(ToolRegistry.ToolResolutionStatus.UNSUPPORTED, unsupported.status());
        assertNull(unsupported.tool());
    }

    public void testRegistryActiveToolsPreservesConfiguredOrderAndDedupes() {
        ToolRegistry registry = new ToolRegistry();
        Tool read = tool("read", ToolRiskLevel.READ_ONLY);
        Tool write = tool("write", ToolRiskLevel.WRITE);
        registry.register(read);
        registry.register(write);

        assertEquals(List.of(write, read), registry.activeTools(List.of("write", "missing", "read", "write")));
    }

    public void testRouterRejectsOutputThatDoesNotMatchOutputSchema() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool<Object, Map<String, Object>>() {
            @Override
            public String name() {
                return "schema_tool";
            }

            @Override
            public String label() {
                return "schema_tool";
            }

            @Override
            public String description() {
                return "schema tool";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
            }

            @Override
            public Map<String, Object> outputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of("ok", Map.of("type", "string")),
                        "required", List.of("ok"),
                        "additionalProperties", false
                );
            }

            @Override
            public Object parseInput(String argumentsJson) {
                return new Object();
            }

            @Override
            public ToolCallResult<Map<String, Object>> call(Object input, ToolUseContext context) {
                return ToolCallResult.success(Map.of("ok", 1));
            }

            @Override
            public ModelToolResult toModelResult(Map<String, Object> output, ToolResultContext<Object, Map<String, Object>> context) {
                return ModelToolResult.text("ok");
            }
        });

        ToolRouter router = new ToolRouter(registry);
        ToolRouter.PreparedToolCall prepared = router.buildInvocation(
                ToolCallContent.builder()
                        .toolCallId("call-1")
                        .toolName("schema_tool")
                        .argumentsJson("{}")
                        .build(),
                null,
                ToolCancellationToken.none(),
                null,
                null,
                null
        );
        ToolCallResult<?> result = router.dispatch(prepared);

        assertTrue(result.hasFailure());
        assertEquals(ToolFailureKind.SCHEMA, result.failure().kind());
        assertTrue(result.failure().message().contains("outputSchema"));
    }

    private Tool tool(ToolRiskLevel riskLevel) {
        return tool("test", riskLevel);
    }

    private Tool tool(String name, ToolRiskLevel riskLevel) {
        return new Tool<Object, String>() {
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
                return "test";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of();
            }

            @Override
            public ToolRiskLevel riskLevel() {
                return riskLevel;
            }

            @Override
            public Object parseInput(String argumentsJson) {
                return new Object();
            }

            @Override
            public ToolCallResult<String> call(Object input, ToolUseContext context) {
                return ToolCallResult.success("ok");
            }

            @Override
            public ModelToolResult toModelResult(String output, ToolResultContext<Object, String> context) {
                return ModelToolResult.text(output);
            }
        };
    }
}
