package io.github.lingjiuu.prompt;

import io.github.lingjiuu.llm.LlmCallOptions;
import io.github.lingjiuu.llm.LlmModel;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.tool.ToolDefinition;

import java.util.List;

public record Prompt(
        String systemPrompt,
        LlmModel model,
        List<ToolDefinition> tools,
        List<Message> messages,
        LlmCallOptions callOptions
) {

    public Prompt {
        tools = tools == null ? List.of() : List.copyOf(tools);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public LlmRequest toLlmRequest(RequestAuth auth) {
        return LlmRequest.builder()
                .systemPrompt(systemPrompt)
                .model(model)
                .tools(tools)
                .messages(messages)
                .callOptions(callOptions)
                .auth(auth)
                .build();
    }
}
