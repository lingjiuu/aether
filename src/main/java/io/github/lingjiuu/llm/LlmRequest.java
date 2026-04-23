package io.github.lingjiuu.llm;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.model.AgentConfig;
import io.github.lingjiuu.provider.RequestAuth;
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

    private AgentConfig config;

    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    private LlmCallOptions callOptions;

    private RequestAuth auth;
}
