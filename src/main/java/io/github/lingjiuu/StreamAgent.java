package io.github.lingjiuu;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import io.github.lingjiuu.ai.AiStreams;
import io.github.lingjiuu.ai.AiModel;
import io.github.lingjiuu.ai.ModelRegistry;
import io.github.lingjiuu.ai.ResolvedRequestAuth;
import io.github.lingjiuu.auth.AuthStorage;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.UserMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.AgentTool;
import io.github.lingjiuu.model.AgentState;
import io.github.lingjiuu.provider.AssistantMessageEvent;
import io.github.lingjiuu.provider.AssistantMessageEventStream;
import io.github.lingjiuu.provider.ProviderOptions;
import io.github.lingjiuu.tool.ToolRegistry;

import java.util.List;
import java.util.Map;

public class StreamAgent {

    private static final String DEFAULT_PROVIDER = "bailian";
    private static final String DEFAULT_MODEL_ID = "qwen3.5-plus-2026-02-15";

    private final AiStreams aiStreams = new AiStreams();
    private final AuthStorage authStorage = AuthStorage.create();
    private final ModelRegistry modelRegistry = new ModelRegistry(authStorage);

    private final AgentState state = AgentState.builder()
            .systemPrompt("You are a helpful assistant")
            .model(resolveInitialModel())
            .build();

    private final ToolRegistry toolRegistry = new ToolRegistry();

    public StreamAgent() {
        AgentTool getTimeTool = AgentTool.builder()
                .name("get_time")
                .description("Get the current time in Asia/Shanghai.")
                .schema(buildGetTimeTool())
                .executor(argumentsJson -> java.time.ZonedDateTime.now().toString())
                .build();

        state.getTools().add(getTimeTool);
        toolRegistry.register(getTimeTool);
    }

    public void runAgentLoop(String content) {
        System.out.println("[AGENT] session start");
        System.out.println("[USER] " + content);

        state.getMessages().add(UserMessage.builder()
                .contents(List.of(
                        TextContent.builder()
                                .text(content)
                                .build()
                ))
                .build());
        System.out.println("[STATE] history+ user");

        int turn = 1;

        while (true) {
            System.out.println();
            System.out.println("[AGENT] turn " + turn + " start");
            System.out.println("[STATE] message_count=" + state.getMessages().size()
                    + ", tool_count=" + toolRegistry.size());

            ResolvedRequestAuth auth = modelRegistry.getApiKeyAndHeaders(state.getModel());
            if (!auth.isOk()) {
                throw new IllegalStateException(auth.getError());
            }

            ProviderOptions options = ProviderOptions.builder()
                    .apiKey(auth.getApiKey())
                    .headers(auth.getHeaders())
                    .reasoning(state.getReasoning())
                    .build();

            List<ToolResultMessage> toolResults = new java.util.ArrayList<>();
            StringBuilder streamedText = new StringBuilder();
            StringBuilder reasoningSummaryText = new StringBuilder();
            AssistantMessage assistantMessage;

            try (AssistantMessageEventStream streaming = aiStreams.streamSimple(state, options)) {
                assistantMessage = streaming.consume(event -> handleAssistantEvent(event, streamedText, reasoningSummaryText));
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to close assistant stream", e);
            }
            System.out.println();

            String assistantText = MessageContents.text(assistantMessage);
            String assistantThinking = MessageContents.thinking(assistantMessage);
            List<ToolCallContent> toolCalls = MessageContents.toolCalls(assistantMessage);
            boolean shouldContinue = !toolCalls.isEmpty();

            System.out.println("[LLM] stop_reason=" + assistantMessage.getStopReason());
            if (!streamedText.isEmpty()) {
                System.out.println("[ASSISTANT] " + streamedText);
            }
            if (!reasoningSummaryText.isEmpty()) {
                System.out.println("[REASONING] summary_delta=" + reasoningSummaryText);
            }
            if (!assistantThinking.isBlank()) {
                System.out.println("[REASONING] summary=" + assistantThinking);
            }

            state.getMessages().add(assistantMessage);
            System.out.println("[STATE] history+ assistant");

            for (ToolCallContent toolCall : toolCalls) {
                System.out.println("[TOOL] call_id=" + toolCall.getToolCallId());
                System.out.println("[TOOL] name=" + toolCall.getToolName());
                System.out.println("[TOOL] arguments=" + toolCall.getArgumentsJson());
                toolResults.add(executeToolCall(toolCall));
            }

            for (ToolResultMessage toolResult : toolResults) {
                state.getMessages().add(toolResult);
                System.out.println("[STATE] history+ tool_result");
            }

            if (!shouldContinue) {
                System.out.println("[AGENT] final answer");
                if (!assistantText.isBlank()) {
                    System.out.println("[ASSISTANT] " + assistantText);
                }
                System.out.println("[AGENT] session end");
                break;
            }

            System.out.println("[AGENT] continue after tool call");
            turn++;
        }
    }

    public ToolResultMessage executeToolCall(ToolCallContent toolCall) {
        System.out.println("[TOOL] executing " + toolCall.getToolName());
        ToolResultMessage result = toolRegistry.execute(toolCall);
        System.out.println("[TOOL] result=" + MessageContents.text(result));
        return result;
    }

    private void handleAssistantEvent(
            AssistantMessageEvent event,
            StringBuilder streamedText,
            StringBuilder reasoningSummaryText
    ) {
        if (event == null || event.getType() == null) {
            return;
        }

        switch (event.getType()) {
            case TEXT_DELTA -> {
                streamedText.append(event.getDelta());
                System.out.print(event.getDelta());
            }
            case THINKING_DELTA -> reasoningSummaryText.append(event.getDelta());
            default -> {
            }
        }
    }

    public FunctionTool buildGetTimeTool() {
        return FunctionTool.builder()
                .name("get_time")
                .description("Get the current time in Asia/Shanghai.")
                .strict(true)
                .parameters(FunctionTool.Parameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of()))
                        .putAdditionalProperty("required", JsonValue.from(List.of()))
                        .build())
                .build();
    }

    private AiModel resolveInitialModel() {
        String provider = System.getenv("AETHER_PROVIDER");
        String modelId = System.getenv("AETHER_MODEL");
        if (provider == null || provider.isBlank()) {
            provider = DEFAULT_PROVIDER;
        }
        if (modelId == null || modelId.isBlank()) {
            modelId = DEFAULT_MODEL_ID;
        }

        AiModel explicit = modelRegistry.find(provider, modelId);
        if (explicit != null) {
            return explicit;
        }

        throw new IllegalStateException("No model configured for " + provider + "/" + modelId + ".");
    }
}
