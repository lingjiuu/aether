package io.github.lingjiuu.session;

import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import junit.framework.TestCase;

import java.util.List;
import java.util.Map;

public class SystemPromptBuilderTest extends TestCase {

    public void testBuildIncludesToolSnippetsAndDedupedGuidelines() {
        SystemPromptBuilder builder = new SystemPromptBuilder();

        String prompt = builder.build("You are helpful.", List.of(
                new PromptTool("grep", "Search file contents", List.of(
                        "Use grep before answering about code.",
                        " Use grep before answering about code. "
                )),
                new PromptTool("quiet", null, List.of("Keep tool output concise."))
        ));

        assertTrue(prompt.startsWith("You are helpful."));
        assertTrue(prompt.contains("Available tools:\n- grep: Search file contents"));
        assertFalse(prompt.contains("- quiet:"));
        assertEquals(1, count(prompt, "- Use grep before answering about code."));
        assertTrue(prompt.contains("- Keep tool output concise."));
    }

    public void testBuildReturnsBasePromptWhenToolsHaveNoPromptMetadata() {
        SystemPromptBuilder builder = new SystemPromptBuilder();

        String prompt = builder.build("Base prompt", List.of(new PromptTool("plain", null, List.of())));

        assertEquals("Base prompt", prompt);
    }

    private int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static final class PromptTool implements ToolDefinition {
        private final String name;
        private final String promptSnippet;
        private final List<String> promptGuidelines;

        private PromptTool(String name, String promptSnippet, List<String> promptGuidelines) {
            this.name = name;
            this.promptSnippet = promptSnippet;
            this.promptGuidelines = promptGuidelines;
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
        public String promptSnippet() {
            return promptSnippet;
        }

        @Override
        public List<String> promptGuidelines() {
            return promptGuidelines;
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.text("ok");
        }
    }
}
