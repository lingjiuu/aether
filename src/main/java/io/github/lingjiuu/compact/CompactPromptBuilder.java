package io.github.lingjiuu.compact;

import io.github.lingjiuu.llm.LlmCallOptions;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.session.SessionConfig;

import java.util.ArrayList;
import java.util.List;

public class CompactPromptBuilder {

    public static final String SUMMARY_PREFIX = "Another language model started to solve this problem and produced a summary of its thinking process. You also have access to the state of the tools that were used by that language model. Use this to build on the work that has already been done and avoid duplicating work. Here is the summary produced by the other language model, use the information in this summary to assist with your own analysis:";

    private static final String SUMMARIZATION_PROMPT = """
            You are performing a CONTEXT CHECKPOINT COMPACTION. Create a handoff summary for another LLM that will resume the task.

            Include:
            - Current progress and key decisions made
            - Important context, constraints, or user preferences
            - What remains to be done (clear next steps)
            - Any critical data, examples, or references needed to continue

            Be concise, structured, and focused on helping the next LLM seamlessly continue the work.
            """;

    public LlmRequest build(SessionConfig config, List<Message> messages) {
        if (config == null || config.model() == null) {
            throw new CompactException("Compact requires a configured model.");
        }
        List<Message> compactMessages = new ArrayList<>();
        if (messages != null) {
            compactMessages.addAll(messages);
        }
        compactMessages.add(UserMessage.builder()
                .contents(List.of(TextContent.builder()
                        .text(SUMMARIZATION_PROMPT.trim())
                        .build()))
                .build());
        return LlmRequest.builder()
                .baseInstructions(config.baseInstructions())
                .model(config.model())
                .tools(List.of())
                .messages(compactMessages)
                .callOptions(LlmCallOptions.builder()
                        .reasoning(config.reasoning())
                        .build())
                .build();
    }
}
