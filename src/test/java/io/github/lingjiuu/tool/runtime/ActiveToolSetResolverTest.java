package io.github.lingjiuu.tool.runtime;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRegistry;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import junit.framework.TestCase;

import java.util.List;
import java.util.Map;

public class ActiveToolSetResolverTest extends TestCase {

    public void testNullActiveNamesActivatesAllRegisteredToolsInRegistryOrder() {
        ToolRegistry registry = registryWith("first", "second");

        ActiveToolSet activeToolSet = new ActiveToolSetResolver().compile(registry, null);

        assertEquals(List.of("first", "second"), activeToolSet.activeToolNames());
        assertEquals("first", activeToolSet.findActive("first").name());
        assertEquals(2, activeToolSet.entries().size());
        assertTrue(activeToolSet.entries().get(0).active());
        assertTrue(activeToolSet.entries().get(1).active());
    }

    public void testExplicitActiveNamesFilterAndOrderActiveTools() {
        ToolRegistry registry = registryWith("first", "second", "third");

        ActiveToolSet activeToolSet = new ActiveToolSetResolver().compile(
                registry,
                List.of("third", "missing", "first", "third")
        );

        assertEquals(List.of("third", "first"), activeToolSet.activeToolNames());
        assertEquals("third", activeToolSet.activeTools().get(0).name());
        assertEquals("first", activeToolSet.activeTools().get(1).name());
        assertNull(activeToolSet.findActive("second"));
        assertEquals("second", activeToolSet.findRegistered("second").name());
        assertFalse(activeToolSet.entries().get(1).active());
        assertEquals("Not active for this session.", activeToolSet.entries().get(1).reason());
    }

    public void testEmptyActiveNamesExposeNoTools() {
        ToolRegistry registry = registryWith("first", "second");

        ActiveToolSet activeToolSet = new ActiveToolSetResolver().compile(registry, List.of());

        assertTrue(activeToolSet.activeTools().isEmpty());
        assertNull(activeToolSet.findActive("first"));
        assertEquals("first", activeToolSet.findRegistered("first").name());
        assertFalse(activeToolSet.entries().get(0).active());
    }

    private ToolRegistry registryWith(String... names) {
        ToolRegistry registry = new ToolRegistry();
        for (String name : names) {
            registry.register(new NamedTool(name));
        }
        return registry;
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
