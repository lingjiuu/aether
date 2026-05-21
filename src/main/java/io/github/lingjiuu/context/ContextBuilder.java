package io.github.lingjiuu.context;

import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.ContextMessage;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.compact.CompactPromptBuilder;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.agent.turn.TurnContext;
import io.github.lingjiuu.provider.ProviderReplayData;
import io.github.lingjiuu.session.SessionConfig;
import io.github.lingjiuu.skill.SkillInjection;
import io.github.lingjiuu.tool.ToolExecutionResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class ContextBuilder {

    private static final String INTERRUPTED_TURN_GUIDANCE = """
            <turn_aborted>
            The previous turn was interrupted by the user. Some tools or commands may have partially executed.
            </turn_aborted>
            """.trim();

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

    public AssistantMessage assistantTextItem(
            AssistantMessage partial,
            String text,
            ProviderReplayData providerState
    ) {
        return assistantItem(
                partial,
                List.of(TextContent.builder()
                        .text(text == null ? "" : text)
                        .build()),
                providerState
        );
    }

    public AssistantMessage assistantThinkingItem(
            AssistantMessage partial,
            String thinking,
            ProviderReplayData providerState
    ) {
        return assistantItem(
                partial,
                List.of(ThinkingContent.builder()
                        .thinking(thinking == null ? "" : thinking)
                        .build()),
                providerState
        );
    }

    public AssistantMessage assistantToolCallItem(
            AssistantMessage partial,
            ToolCallContent toolCall,
            ProviderReplayData providerState
    ) {
        if (toolCall == null) {
            throw new IllegalArgumentException("tool call must not be null");
        }
        return assistantItem(
                partial,
                List.of(copyToolCall(toolCall)),
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

    public EnvironmentContext environmentContext(Path cwd) {
        return EnvironmentContext.from(cwd);
    }

    public InitialContextSnapshot initialContextSnapshot(
            SessionConfig config,
            TurnContext turnContext
    ) {
        Path cwd = turnContext != null && turnContext.cwd() != null
                ? turnContext.cwd()
                : config == null ? null : config.cwd();
        return new InitialContextSnapshot(environmentContext(cwd));
    }

    public List<ContextMessage> initialContextMessages(
            InitialContextSnapshot previous,
            InitialContextSnapshot current
    ) {
        if (current == null) {
            return List.of();
        }
        Optional<ContextMessage> environmentMessage = environmentMessage(
                previous == null ? null : previous.environment(),
                current.environment()
        );
        return environmentMessage.map(List::of).orElseGet(List::of);
    }

    public List<ContextMessage> fullInitialContextMessages(InitialContextSnapshot current) {
        return initialContextMessages(null, current);
    }

    public Optional<ContextMessage> environmentMessage(
            EnvironmentContext previous,
            EnvironmentContext current
    ) {
        if (current == null) {
            return Optional.empty();
        }

        List<String> lines = current.diffLines(previous);
        if (lines.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder text = new StringBuilder();
        text.append(previous == null ? "Environment context:\n" : "Environment context update:\n");
        for (String line : lines) {
            text.append(line).append('\n');
        }
        return Optional.of(ContextMessage.builder()
                .kind(ContextMessage.ContextKind.ENVIRONMENT)
                .contents(List.of(TextContent.builder()
                        .text(text.toString())
                        .build()))
                .build());
    }

    public ContextMessage skillContextMessage(SkillInjection injection) {
        if (injection == null) {
            throw new IllegalArgumentException("skill injection must not be null");
        }
        StringBuilder text = new StringBuilder();
        text.append("<skill>\n");
        text.append("<name>").append(injection.name()).append("</name>\n");
        text.append("<path>").append(injection.path()).append("</path>\n");
        text.append(injection.contents() == null ? "" : injection.contents().trim()).append('\n');
        text.append("</skill>");
        return ContextMessage.builder()
                .kind(ContextMessage.ContextKind.SKILL)
                .contents(List.of(TextContent.builder()
                        .text(text.toString())
                        .build()))
                .build();
    }

    public ContextMessage interruptedTurnMessage() {
        return ContextMessage.builder()
                .kind(ContextMessage.ContextKind.INFORMATIONAL)
                .contents(List.of(TextContent.builder()
                        .text(INTERRUPTED_TURN_GUIDANCE)
                        .build()))
                .build();
    }

    private AssistantMessage assistantItem(
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

    private ToolCallContent copyToolCall(ToolCallContent toolCall) {
        return ToolCallContent.builder()
                .toolCallId(toolCall.getToolCallId())
                .toolName(toolCall.getToolName())
                .argumentsJson(toolCall.getArgumentsJson())
                .arguments(toolCall.getArguments())
                .build();
    }

}
