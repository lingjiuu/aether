package io.github.lingjiuu.tool;

import junit.framework.TestCase;

import java.util.Map;

public class ToolDefinitionTest extends TestCase {

    public void testReadOnlyToolsDefaultToParallelSafe() {
        assertTrue(definition(ToolRiskLevel.READ_ONLY).supportsParallelToolCalls());
    }

    public void testWriteAndExecToolsDefaultToNonParallel() {
        assertFalse(definition(ToolRiskLevel.WRITE).supportsParallelToolCalls());
        assertFalse(definition(ToolRiskLevel.EXEC).supportsParallelToolCalls());
        assertFalse(definition(ToolRiskLevel.UNKNOWN).supportsParallelToolCalls());
    }

    private ToolDefinition definition(ToolRiskLevel riskLevel) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public String label() {
                return "test";
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
            public ToolExecutionResult execute(ToolExecutionContext context) {
                return ToolExecutionResult.text("ok");
            }
        };
    }
}
