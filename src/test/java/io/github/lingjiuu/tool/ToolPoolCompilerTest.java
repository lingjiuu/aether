package io.github.lingjiuu.tool;

import junit.framework.TestCase;

import java.util.List;
import java.util.Map;

public class ToolPoolCompilerTest extends TestCase {

    public void testCompileReturnsRegisteredToolsInRegistryOrder() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new NamedTool("first"));
        registry.register(new NamedTool("second"));

        List<ToolDefinition> tools = new ToolPoolCompiler().compile(registry);

        assertEquals(2, tools.size());
        assertEquals("first", tools.get(0).name());
        assertEquals("second", tools.get(1).name());
    }

    private static final class NamedTool implements ToolDefinition {
        private final String name;

        private NamedTool(String name) {
            this.name = name;
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
            return Map.of("type", "object");
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.text(name);
        }
    }
}
