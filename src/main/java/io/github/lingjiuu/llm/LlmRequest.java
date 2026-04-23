package io.github.lingjiuu.llm;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.provider.RequestAuth;
import io.github.lingjiuu.tool.ToolDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {

    private String systemPrompt;

    private LlmModel model;

    @Builder.Default
    private List<ToolDefinition> tools = new ArrayList<>();

    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    private LlmCallOptions callOptions;

    private RequestAuth auth;
}
