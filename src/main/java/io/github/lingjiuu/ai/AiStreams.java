package io.github.lingjiuu.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.message.AgentMessage;
import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.model.AgentState;
import io.github.lingjiuu.model.AgentTool;
import io.github.lingjiuu.provider.AssistantMessageEventStream;
import io.github.lingjiuu.provider.OpenAiResponsesProvider;
import io.github.lingjiuu.provider.Provider;
import io.github.lingjiuu.provider.ProviderContext;
import io.github.lingjiuu.provider.ProviderOptions;
import io.github.lingjiuu.provider.ProviderTool;

import java.util.ArrayList;
import java.util.List;

public class AiStreams {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ProviderRegistry providerRegistry;

    public AiStreams() {
        this(defaultRegistry());
    }

    public AiStreams(ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public AssistantMessageEventStream streamSimple(AgentState state, ProviderOptions options) {
        Provider provider = providerRegistry.require(state.getModel().getApi());
        return provider.streamSimple(
                state.getModel(),
                buildContext(state),
                mergeOptions(state, options)
        );
    }

    private ProviderContext buildContext(AgentState state) {
        List<Message> messages = new ArrayList<>();
        for (AgentMessage agentMessage : state.getMessages()) {
            if (agentMessage instanceof Message message) {
                messages.add(message);
            }
        }

        List<ProviderTool> tools = new ArrayList<>();
        for (AgentTool tool : state.getTools()) {
            JsonNode parameters = null;
            if (tool.getSchema().parameters().isPresent()) {
                parameters = objectMapper.valueToTree(tool.getSchema().parameters().get());
            }
            tools.add(ProviderTool.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .strict(tool.getSchema().strict().orElse(null))
                    .parameters(parameters)
                    .build());
        }

        return ProviderContext.builder()
                .systemPrompt(state.getSystemPrompt())
                .messages(messages)
                .tools(tools)
                .build();
    }

    private ProviderOptions mergeOptions(AgentState state, ProviderOptions options) {
        ProviderOptions safeOptions = options == null ? ProviderOptions.builder().build() : options;
        return ProviderOptions.builder()
                .apiKey(safeOptions.getApiKey())
                .headers(safeOptions.getHeaders())
                .temperature(safeOptions.getTemperature())
                .maxTokens(safeOptions.getMaxTokens())
                .reasoning(safeOptions.getReasoning() != null ? safeOptions.getReasoning() : state.getReasoning())
                .build();
    }

    private static ProviderRegistry defaultRegistry() {
        return new ProviderRegistry()
                .register(new OpenAiResponsesProvider());
    }
}
