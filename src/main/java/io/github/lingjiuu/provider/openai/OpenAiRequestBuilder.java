package io.github.lingjiuu.provider.openai;

import com.openai.models.ReasoningEffort;
import com.openai.models.responses.ResponseCreateParams;
import io.github.lingjiuu.llm.LlmCallOptions;
import io.github.lingjiuu.llm.LlmRequest;
import io.github.lingjiuu.llm.ReasoningOptions;
import io.github.lingjiuu.provider.protocol.DefaultRequestFinalizer;
import io.github.lingjiuu.provider.protocol.DefaultRequestMessageNormalizer;
import io.github.lingjiuu.provider.protocol.NormalizedRequestMessage;
import io.github.lingjiuu.provider.protocol.RequestFinalizer;
import io.github.lingjiuu.provider.protocol.RequestMessageNormalizer;
import io.github.lingjiuu.tool.ToolDefinition;

import java.util.List;

public class OpenAiRequestBuilder {

    private final RequestMessageNormalizer messageNormalizer;
    private final RequestFinalizer requestFinalizer;
    private final OpenAiMessageAdapter messageAdapter;
    private final OpenAiToolSchemaBuilder toolSchemaBuilder;

    public OpenAiRequestBuilder() {
        this(
                new DefaultRequestMessageNormalizer(),
                new DefaultRequestFinalizer(),
                new OpenAiMessageAdapter(),
                new OpenAiToolSchemaBuilder()
        );
    }

    OpenAiRequestBuilder(
            RequestMessageNormalizer messageNormalizer,
            RequestFinalizer requestFinalizer,
            OpenAiMessageAdapter messageAdapter,
            OpenAiToolSchemaBuilder toolSchemaBuilder
    ) {
        this.messageNormalizer = messageNormalizer;
        this.requestFinalizer = requestFinalizer;
        this.messageAdapter = messageAdapter;
        this.toolSchemaBuilder = toolSchemaBuilder;
    }

    public ResponseCreateParams buildRequest(LlmRequest request) {
        if (request == null || request.getModel() == null) {
            throw new IllegalArgumentException("request model must not be null");
        }

        LlmCallOptions safeOptions = request.getCallOptions() == null ? LlmCallOptions.builder().build() : request.getCallOptions();
        List<NormalizedRequestMessage> normalizedMessages = requestFinalizer.finalizeMessages(
                messageNormalizer.normalize(request.getMessages(), request.getTools())
        );
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(request.getModel().getId())
                .store(false)
                .inputOfResponse(messageAdapter.toInputItems(request.getSystemPrompt(), normalizedMessages));

        if (safeOptions.getTemperature() != null) {
            builder.temperature(safeOptions.getTemperature());
        }
        if (safeOptions.getMaxTokens() != null) {
            builder.maxOutputTokens(safeOptions.getMaxTokens().longValue());
        }
        if (safeOptions.getReasoning() != null) {
            builder.reasoning(toOpenAiReasoning(safeOptions.getReasoning()));
        }

        if (request.getTools() != null) {
            for (ToolDefinition tool : request.getTools()) {
                if (tool != null) {
                    builder.addTool(toolSchemaBuilder.build(tool));
                }
            }
        }

        return builder.build();
    }

    private com.openai.models.Reasoning toOpenAiReasoning(ReasoningOptions reasoning) {
        com.openai.models.Reasoning.Builder builder = com.openai.models.Reasoning.builder();
        if (reasoning.getReasoningEffort() != null) {
            builder.effort(mapReasoningEffort(reasoning.getReasoningEffort()));
        }
        if (reasoning.getReasoningSummaryEffort() != null) {
            builder.generateSummary(mapSummaryEffort(reasoning.getReasoningSummaryEffort()));
        }
        return builder.build();
    }

    private ReasoningEffort mapReasoningEffort(ReasoningOptions.ReasoningEffort effort) {
        return switch (effort) {
            case NONE -> ReasoningEffort.NONE;
            case MINIMAL -> ReasoningEffort.MINIMAL;
            case LOW -> ReasoningEffort.LOW;
            case MEDIUM -> ReasoningEffort.MEDIUM;
            case HIGH -> ReasoningEffort.HIGH;
            case XHIGH -> ReasoningEffort.XHIGH;
        };
    }

    private com.openai.models.Reasoning.GenerateSummary mapSummaryEffort(ReasoningOptions.ReasoningSummaryEffort summaryEffort) {
        return switch (summaryEffort) {
            case AUTO -> com.openai.models.Reasoning.GenerateSummary.AUTO;
            case CONCISE -> com.openai.models.Reasoning.GenerateSummary.CONCISE;
            case DETAILED -> com.openai.models.Reasoning.GenerateSummary.DETAILED;
        };
    }
}
