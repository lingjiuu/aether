package io.github.lingjiuu.tool;

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

    private Tool tool(ToolRiskLevel riskLevel) {
        return tool("test", riskLevel);
    }

    private Tool tool(String name, ToolRiskLevel riskLevel) {
        return new Tool() {
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
            public Map<String, Object> parametersSchema() {
                return Map.of();
            }

            @Override
            public ToolRiskLevel riskLevel() {
                return riskLevel;
            }

            @Override
            public ToolExecutionResult execute(ToolInvocation context) {
                return ToolExecutionResult.text("ok");
            }
        };
    }
}
