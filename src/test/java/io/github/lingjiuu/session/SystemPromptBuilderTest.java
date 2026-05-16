package io.github.lingjiuu.session;

import io.github.lingjiuu.resource.ContextFile;
import io.github.lingjiuu.resource.Skill;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionMode;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolUpdateCallback;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.time.LocalDate;
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

    public void testBuildOptionsIncludeAppendContextSkillsDateAndCwd() {
        SystemPromptBuilder builder = new SystemPromptBuilder();

        String prompt = builder.build(SystemPromptBuildOptions.builder()
                .customPrompt("Base prompt")
                .appendPrompt("Append prompt")
                .cwd(Path.of("/tmp/aether-demo"))
                .currentDate(LocalDate.of(2026, 5, 16))
                .activeTools(List.of(
                        new PromptTool("bash", "Run shell commands", List.of()),
                        new PromptTool("grep", "Search file contents", List.of("Use grep first."))
                ))
                .contextFiles(List.of(ContextFile.builder()
                        .path(Path.of("/tmp/aether-demo/AGENTS.md"))
                        .content("Project rule")
                        .build()))
                .skills(List.of(
                        Skill.builder()
                                .name("java-test")
                                .description("Run Java tests")
                                .location(Path.of("/tmp/aether-demo/.aether/skills/java-test/SKILL.md"))
                                .build(),
                        Skill.builder()
                                .name("hidden")
                                .description("Hidden skill")
                                .location(Path.of("/tmp/aether-demo/.aether/skills/hidden/SKILL.md"))
                                .disableModelInvocation(true)
                                .build()
                ))
                .build());

        assertOrdered(prompt,
                "Base prompt",
                "Append prompt",
                "Available tools:",
                "Tool guidelines:",
                "# Project Context",
                "<available_skills>",
                "Current date: 2026-05-16",
                "Current working directory: /tmp/aether-demo"
        );
        assertTrue(prompt.contains("- bash: Run shell commands"));
        assertTrue(prompt.contains("- grep: Search file contents"));
        assertTrue(prompt.contains("Prefer grep/find/ls tools over bash for file exploration"));
        assertTrue(prompt.contains("- Use grep first."));
        assertTrue(prompt.contains("## /tmp/aether-demo/AGENTS.md"));
        assertTrue(prompt.contains("Project rule"));
        assertTrue(prompt.contains("<name>java-test</name>"));
        assertTrue(prompt.contains("<description>Run Java tests</description>"));
        assertTrue(prompt.contains("<location>/tmp/aether-demo/.aether/skills/java-test/SKILL.md</location>"));
        assertFalse(prompt.contains("<name>hidden</name>"));
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

    private void assertOrdered(String text, String... parts) {
        int index = -1;
        for (String part : parts) {
            int next = text.indexOf(part, index + 1);
            assertTrue("Expected to find after previous part: " + part + "\nPrompt:\n" + text, next > index);
            index = next;
        }
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
        public ToolExecutionMode executionMode() {
            return ToolExecutionMode.PARALLEL_SAFE;
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
            return ToolExecutionResult.text("ok");
        }
    }
}
