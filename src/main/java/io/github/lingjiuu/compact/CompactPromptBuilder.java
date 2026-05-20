package io.github.lingjiuu.compact;

import io.github.lingjiuu.llm.LlmCallOptions;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.prompt.Prompt;
import io.github.lingjiuu.session.SessionConfig;

import java.util.List;

public class CompactPromptBuilder {

    public static final String SUMMARY_PREFIX = "Another language model started to solve this problem and produced a summary of its thinking process. You also have access to the state of the tools that were used by that language model. Use this to build on the work that has already been done and avoid duplicating work. Here is the summary produced by the other language model, use the information in this summary to assist with your own analysis:";

    private static final String SYSTEM_PROMPT = """
            You are compressing an agent conversation for future continuation.
            Produce a concise but complete summary that preserves:
            - the user's goal and constraints
            - important decisions and assumptions
            - files, commands, tools, and results that matter
            - open tasks and the current state
            Do not invent facts. Prefer structured bullets when useful.
            """;

    public Prompt build(SessionConfig config, List<Message> messages) {
        if (config == null || config.model() == null) {
            throw new CompactException("Compact requires a configured model.");
        }
        return new Prompt(
                SYSTEM_PROMPT,
                config.model(),
                List.of(),
                List.of(UserMessage.builder()
                        .contents(List.of(TextContent.builder()
                                .text(renderConversation(messages))
                                .build()))
                        .build()),
                LlmCallOptions.builder()
                        .reasoning(config.reasoning())
                        .build()
        );
    }

    private String renderConversation(List<Message> messages) {
        StringBuilder rendered = new StringBuilder();
        rendered.append("Summarize this conversation for context compaction.\n\n");
        if (messages == null || messages.isEmpty()) {
            rendered.append("(empty conversation)");
            return rendered.toString();
        }
        int index = 1;
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            rendered.append("## Message ").append(index++).append(" [").append(message.role()).append("]\n");
            String text = MessageContents.text(message);
            if (!text.isBlank()) {
                rendered.append(text).append('\n');
            }
            if (message instanceof io.github.lingjiuu.message.AssistantMessage assistantMessage) {
                List<ToolCallContent> toolCalls = MessageContents.toolCalls(assistantMessage);
                for (ToolCallContent toolCall : toolCalls) {
                    rendered.append("Tool call: ")
                            .append(toolCall.getToolName())
                            .append(" call_id=")
                            .append(toolCall.getToolCallId())
                            .append(" args=")
                            .append(toolCall.getArgumentsJson())
                            .append('\n');
                }
            }
            rendered.append('\n');
        }
        return rendered.toString();
    }
}
