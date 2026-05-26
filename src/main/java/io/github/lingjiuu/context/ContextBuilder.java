package io.github.lingjiuu.context;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.provider.ProviderReplayData;
import io.github.lingjiuu.skill.Skill;
import io.github.lingjiuu.skill.SkillInjection;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionResult;

import java.nio.file.Path;
import java.util.List;

public class ContextBuilder {

    public ToolResultMessage toolResultMessage(ToolCallContent toolCall, ToolExecutionResult result) {
        if (toolCall == null) {
            throw new IllegalArgumentException("tool call must not be null");
        }
        ToolExecutionResult safeResult = result == null
                ? ToolExecutionResult.errorText("Tool returned no result.")
                : result;
        return ToolResultMessage.builder()
                .toolCallId(toolCall.getToolCallId())
                .toolName(toolCall.getToolName())
                .details(safeResult.getDetails())
                .isError(safeResult.isError())
                .contents(safeResult.getContents() == null ? List.of() : safeResult.getContents())
                .build();
    }

    public AssistantMessage assistantTextMessage(
            AssistantMessage partial,
            String text,
            ProviderReplayData providerState
    ) {
        return assistantMessage(
                partial,
                List.of(TextContent.builder()
                        .text(text == null ? "" : text)
                        .build()),
                providerState
        );
    }

    public AssistantMessage assistantThinkingMessage(
            AssistantMessage partial,
            String thinking,
            ProviderReplayData providerState
    ) {
        return assistantMessage(
                partial,
                List.of(ThinkingContent.builder()
                        .thinking(thinking == null ? "" : thinking)
                        .build()),
                providerState
        );
    }

    public AssistantMessage assistantToolCallMessage(
            AssistantMessage partial,
            ToolCallContent toolCall,
            ProviderReplayData providerState
    ) {
        if (toolCall == null) {
            throw new IllegalArgumentException("tool call must not be null");
        }
        return assistantMessage(
                partial,
                List.of(ToolCallContent.builder()
                        .toolCallId(toolCall.getToolCallId())
                        .toolName(toolCall.getToolName())
                        .argumentsJson(toolCall.getArgumentsJson())
                        .arguments(toolCall.getArguments())
                        .build()),
                providerState
        );
    }

    public UserMessage userMessage(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("prompt content must not be blank");
        }
        return UserMessage.builder()
                .contents(List.of(TextContent.builder()
                        .text(content)
                        .build()))
                .build();
    }

    private AssistantMessage assistantMessage(
            AssistantMessage partial,
            List<MessageContent> contents,
            ProviderReplayData providerState
    ) {
        return AssistantMessage.builder()
                .responseId(partial == null ? null : partial.getResponseId())
                .provider(partial == null ? null : partial.getProvider())
                .model(partial == null ? null : partial.getModel())
                .contents(contents == null ? List.of() : contents)
                .providerState(providerState)
                .build();
    }

    private ContextMessage contextMessage(ContextMessage.ContextKind kind, String text) {
        return ContextMessage.builder()
                .kind(kind)
                .contents(List.of(TextContent.builder()
                        .text(text)
                        .build()))
                .build();
    }

    // Hidden model-context templates. Keep context-only shapes here so regular transcript message
    // builders above stay separate from model-visible steering/context fragments.
    private static final String TURN_ABORTED_CONTEXT_TEMPLATE = """
            <turn_aborted>
            %s
            </turn_aborted>
            """.trim();

    private static final String TURN_ABORTED_CONTEXT_GUIDANCE =
            "The previous turn was interrupted by the user. Some tools or commands may have partially executed.";

    private static final String ENVIRONMENT_CONTEXT_TEMPLATE = """
            <environment_context>
            %s
            </environment_context>
            """.trim();

    private static final String ADDITIONAL_INSTRUCTIONS_CONTEXT_TEMPLATE = """
            <additional_instructions>
            %s
            </additional_instructions>
            """.trim();

    private static final String TOOL_CONTEXT_TEMPLATE = """
            <tool_context>
            %s
            </tool_context>
            """.trim();

    private static final String AVAILABLE_SKILLS_CONTEXT_TEMPLATE = """
            <available_skills>
            %s
            </available_skills>
            """.trim();

    private static final String ENVIRONMENT_CONTEXT_FIELD_TEMPLATE = "  <%s>%s</%s>";

    private static final String SKILL_CONTEXT_TEMPLATE = """
            <skill>
            <name>%s</name>
            <path>%s</path>
            %s
            </skill>
            """.trim();

    public ContextMessage additionalInstructionsMessage(String instructions) {
        return contextMessage(
                ContextMessage.ContextKind.INFORMATIONAL,
                ADDITIONAL_INSTRUCTIONS_CONTEXT_TEMPLATE.formatted(instructions.trim())
        );
    }

    public ContextMessage toolInstructionsMessage(List<ToolDefinition> activeTools) {
        return contextMessage(
                ContextMessage.ContextKind.INFORMATIONAL,
                TOOL_CONTEXT_TEMPLATE.formatted(ContextMessageText.toolInstructions(activeTools))
        );
    }

    public ContextMessage userInstructionsMessage(Path directory, String instructions) {
        return contextMessage(
                ContextMessage.ContextKind.RESOURCE,
                ContextMessageText.userInstructions(directory, instructions)
        );
    }

    public ContextMessage availableSkillsMessage(List<Skill> skills) {
        return contextMessage(
                ContextMessage.ContextKind.SKILL,
                AVAILABLE_SKILLS_CONTEXT_TEMPLATE.formatted(ContextMessageText.availableSkills(skills))
        );
    }

    public ContextMessage environmentContextMessage(List<EnvironmentContext.Field> fields) {
        List<String> lines = (fields == null ? List.<EnvironmentContext.Field>of() : fields)
                .stream()
                .map(field -> ENVIRONMENT_CONTEXT_FIELD_TEMPLATE.formatted(
                        field.name(),
                        field.value() == null ? "" : field.value(),
                        field.name()
                ))
                .toList();
        return contextMessage(
                ContextMessage.ContextKind.ENVIRONMENT,
                ENVIRONMENT_CONTEXT_TEMPLATE.formatted(String.join("\n", lines))
        );
    }

    public ContextMessage skillContextMessage(SkillInjection injection) {
        if (injection == null) {
            throw new IllegalArgumentException("skill injection must not be null");
        }
        return contextMessage(
                ContextMessage.ContextKind.SKILL,
                SKILL_CONTEXT_TEMPLATE.formatted(
                        injection.name() == null ? "" : injection.name(),
                        injection.path() == null ? "" : injection.path().toString(),
                        injection.contents() == null ? "" : injection.contents().trim()
                )
        );
    }

    public ContextMessage interruptedTurnMessage() {
        return contextMessage(
                ContextMessage.ContextKind.INFORMATIONAL,
                TURN_ABORTED_CONTEXT_TEMPLATE.formatted(TURN_ABORTED_CONTEXT_GUIDANCE)
        );
    }

}
