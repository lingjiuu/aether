package io.github.lingjiuu.context;

import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.compact.CompactPromptBuilder;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.provider.ProviderReplayData;
import io.github.lingjiuu.skill.SkillInjection;
import io.github.lingjiuu.tool.ToolExecutionResult;

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

    public UserMessage compactSummaryMessage(String summary) {
        String safeSummary = summary == null || summary.isBlank()
                ? "(compact summary was empty)"
                : summary.trim();
        return userMessage(CompactPromptBuilder.SUMMARY_PREFIX + "\n" + safeSummary);
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

    private static final String ENVIRONMENT_CONTEXT_FIELD_TEMPLATE = "  <%s>%s</%s>";

    private static final String SKILL_CONTEXT_TEMPLATE = """
            <skill>
            <name>%s</name>
            <path>%s</path>
            %s
            </skill>
            """.trim();

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
