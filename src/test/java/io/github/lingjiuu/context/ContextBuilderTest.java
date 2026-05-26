package io.github.lingjiuu.context;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.skill.Skill;
import io.github.lingjiuu.skill.SkillInjection;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import junit.framework.TestCase;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

public class ContextBuilderTest extends TestCase {

    public void testEnvironmentContextMessageBuildsFullContext() {
        ContextBuilder builder = new ContextBuilder();
        EnvironmentContext current = environmentContext("/tmp/aether", "2026-05-20", "UTC");

        ContextMessage message = builder.environmentContextMessage(current.fullFields());

        String text = MessageContents.text(message);
        assertTrue(text.startsWith("<environment_context>"));
        assertTrue(text.contains("<cwd>/tmp/aether</cwd>"));
        assertTrue(text.contains("<shell>zsh</shell>"));
        assertTrue(text.contains("<current_date>2026-05-20</current_date>"));
        assertTrue(text.contains("<timezone>UTC</timezone>"));
        assertTrue(text.endsWith("</environment_context>"));
    }

    public void testEnvironmentContextMessageBuildsOnlyDiffFields() {
        ContextBuilder builder = new ContextBuilder();
        EnvironmentContext previous = environmentContext("/tmp/old", "2026-05-20", "UTC");
        EnvironmentContext current = environmentContext("/tmp/new", "2026-05-20", "UTC");

        ContextMessage message = builder.environmentContextMessage(current.diffFields(previous));

        String text = MessageContents.text(message);
        assertTrue(text.startsWith("<environment_context>"));
        assertTrue(text.contains("<cwd>/tmp/new</cwd>"));
        assertFalse(text.contains("current_date"));
        assertFalse(text.contains("timezone"));
        assertFalse(text.contains("<shell>"));
    }

    public void testEnvironmentContextDiffSkipsUnchangedContext() {
        EnvironmentContext previous = environmentContext("/tmp/aether", "2026-05-20", "UTC");
        EnvironmentContext current = environmentContext("/tmp/aether", "2026-05-20", "UTC");

        assertTrue(current.diffFields(previous).isEmpty());
    }

    public void testAssistantToolCallMessageBuildsSingleMessage() {
        ContextBuilder builder = new ContextBuilder();
        AssistantMessage partial = AssistantMessage.builder()
                .responseId("resp-1")
                .provider("openai")
                .model("gpt-test")
                .build();
        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId("call-1")
                .toolName("read")
                .argumentsJson("{\"path\":\"README.md\"}")
                .build();

        AssistantMessage message = builder.assistantToolCallMessage(partial, toolCall, null);

        assertEquals("resp-1", message.getResponseId());
        assertEquals("openai", message.getProvider());
        assertEquals("gpt-test", message.getModel());
        assertEquals(1, message.getContents().size());
        assertTrue(message.getContents().getFirst() instanceof ToolCallContent);
        ToolCallContent copied = (ToolCallContent) message.getContents().getFirst();
        assertEquals("call-1", copied.getToolCallId());
        assertEquals("read", copied.getToolName());
        assertEquals("{\"path\":\"README.md\"}", copied.getArgumentsJson());
    }

    public void testSkillContextMessageUsesCodexStyleTemplate() {
        ContextBuilder builder = new ContextBuilder();
        SkillInjection injection = new SkillInjection(
                "demo",
                Path.of("/tmp/demo/SKILL.md"),
                "# Demo\nUse the demo workflow.\n"
        );

        ContextMessage message = builder.skillContextMessage(injection);
        String text = MessageContents.text(message);

        assertEquals(ContextMessage.ContextKind.SKILL, message.getKind());
        assertTrue(text.startsWith("<skill>"));
        assertTrue(text.contains("<name>demo</name>"));
        assertTrue(text.contains("<path>/tmp/demo/SKILL.md</path>"));
        assertTrue(text.contains("# Demo\nUse the demo workflow."));
        assertTrue(text.endsWith("</skill>"));
    }

    public void testInitialContextMessagesUseHiddenTemplates() {
        ContextBuilder builder = new ContextBuilder();

        String additional = MessageContents.text(builder.additionalInstructionsMessage("Prefer small diffs."));
        String tools = MessageContents.text(builder.toolInstructionsMessage(List.of(toolDefinition())));
        String project = MessageContents.text(builder.userInstructionsMessage(Path.of("/tmp/aether"), "Project rules."));
        String skills = MessageContents.text(builder.availableSkillsMessage(List.of(Skill.builder()
                .name("demo")
                .description("Demo workflow")
                .location(Path.of("/tmp/demo/SKILL.md"))
                .build())));

        assertTrue(additional.startsWith("<additional_instructions>"));
        assertTrue(additional.contains("Prefer small diffs."));
        assertTrue(tools.startsWith("<tool_context>"));
        assertTrue(tools.contains("Available tools:"));
        assertTrue(tools.contains("Tool guidelines:"));
        assertTrue(project.startsWith("# AGENTS.md instructions for "));
        assertTrue(project.contains("<INSTRUCTIONS>"));
        assertTrue(project.contains("Project rules."));
        assertTrue(skills.startsWith("<available_skills>"));
        assertTrue(skills.contains("demo: Demo workflow"));
    }

    public void testInterruptedTurnMessageUsesCodexStyleTemplate() {
        ContextBuilder builder = new ContextBuilder();

        ContextMessage message = builder.interruptedTurnMessage();
        String text = MessageContents.text(message);

        assertEquals(ContextMessage.ContextKind.INFORMATIONAL, message.getKind());
        assertTrue(text.startsWith("<turn_aborted>"));
        assertTrue(text.contains("The previous turn was interrupted by the user."));
        assertTrue(text.endsWith("</turn_aborted>"));
    }

    private EnvironmentContext environmentContext(String cwd, String currentDate, String timezone) {
        return new EnvironmentContext(
                Path.of(cwd),
                "zsh",
                LocalDate.parse(currentDate),
            ZoneId.of(timezone)
        );
    }

    private ToolDefinition toolDefinition() {
        return new ToolDefinition() {
            @Override
            public String name() {
                return "demo";
            }

            @Override
            public String label() {
                return "Demo";
            }

            @Override
            public String description() {
                return "Demo tool";
            }

            @Override
            public Map<String, Object> parametersSchema() {
                return Map.of();
            }

            @Override
            public String promptSnippet() {
                return "Use demo carefully.";
            }

            @Override
            public List<String> promptGuidelines() {
                return List.of("Do not guess.");
            }

            @Override
            public ToolExecutionResult execute(ToolExecutionContext context) {
                return ToolExecutionResult.text("ok");
            }
        };
    }
}
