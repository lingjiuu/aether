package io.github.lingjiuu.ai;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.model.AgentConfig;
import io.github.lingjiuu.provider.ProviderOptions;
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
public class AssistantRequest {

    private AgentConfig config;

    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    private ProviderOptions options;
}
