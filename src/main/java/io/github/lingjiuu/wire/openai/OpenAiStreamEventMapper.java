package io.github.lingjiuu.wire.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.errors.OpenAIException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseStreamEvent;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.client.AssistantStreamEvent;
import io.github.lingjiuu.model.client.ModelErrorCode;
import io.github.lingjiuu.model.client.ModelErrorInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenAiStreamEventMapper {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final OpenAiReplayCodec replayCodec = new OpenAiReplayCodec(objectMapper);
    private final Map<String, Integer> contentIndexesByItemId = new LinkedHashMap<>();
    private final Map<String, String> partialToolArguments = new LinkedHashMap<>();
    private final List<OpenAiReplayData.ReplayItem> replayItems = new ArrayList<>();
    private final String provider;
    private final AssistantMessage partial;

    OpenAiStreamEventMapper(String model, String provider) {
        this.provider = provider;
        this.partial = AssistantMessage.builder()
                .contents(new ArrayList<>())
                .stopReason(AssistantMessage.StopReason.STOP)
                .model(model)
                .provider(provider)
                .build();
    }

    AssistantStreamEvent map(ResponseStreamEvent event) {
        if (event.created().isPresent()) {
            Response response = event.created().get().response();
            partial.setResponseId(response.id());
            partial.setProvider(provider);
            partial.setModel(response.model().toString());
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.START)
                    .partial(copyMessage(partial))
                    .build();
        }

        if (event.outputItemAdded().isPresent()) {
            var added = event.outputItemAdded().get();
            if (added.item().isReasoning()) {
                var item = added.item().asReasoning();
                int contentIndex = appendContent(ThinkingContent.builder().thinking("").build());
                contentIndexesByItemId.put(item.id(), contentIndex);
                return AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.THINKING_START)
                        .itemId(item.id())
                        .contentIndex(contentIndex)
                        .partial(copyMessage(partial))
                        .build();
            }
            if (added.item().isMessage()) {
                var item = added.item().asMessage();
                int contentIndex = appendContent(TextContent.builder().text("").build());
                contentIndexesByItemId.put(item.id(), contentIndex);
                return AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.TEXT_START)
                        .itemId(item.id())
                        .contentIndex(contentIndex)
                        .partial(copyMessage(partial))
                        .build();
            }
            if (added.item().isFunctionCall()) {
                var item = added.item().asFunctionCall();
                String itemId = item.id().orElse(item.callId());
                ToolCallContent toolCall = ToolCallContent.builder()
                        .toolCallId(item.callId())
                        .toolName(item.name())
                        .argumentsJson(item.arguments())
                        .arguments(parseStreamingJson(item.arguments()))
                        .build();
                int contentIndex = appendContent(toolCall);
                contentIndexesByItemId.put(itemId, contentIndex);
                partialToolArguments.put(itemId, nullToEmpty(item.arguments()));
                return AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.TOOLCALL_START)
                        .itemId(itemId)
                        .toolCallId(item.callId())
                        .toolName(item.name())
                        .contentIndex(contentIndex)
                        .toolCall(copyToolCall(toolCall))
                        .partial(copyMessage(partial))
                        .build();
            }
            return null;
        }

        if (event.outputTextDelta().isPresent()) {
            var delta = event.outputTextDelta().get();
            Integer contentIndex = contentIndexesByItemId.get(delta.itemId());
            if (contentIndex == null) {
                return null;
            }
            TextContent textContent = (TextContent) partial.getContents().get(contentIndex);
            partial.getContents().set(contentIndex, TextContent.builder()
                    .text(nullToEmpty(textContent.getText()) + nullToEmpty(delta.delta()))
                    .build());
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_DELTA)
                    .itemId(delta.itemId())
                    .contentIndex(contentIndex)
                    .delta(delta.delta())
                    .partial(copyMessage(partial))
                    .build();
        }

        if (event.reasoningSummaryTextDelta().isPresent()) {
            var delta = event.reasoningSummaryTextDelta().get();
            Integer contentIndex = contentIndexesByItemId.get(delta.itemId());
            if (contentIndex == null) {
                return null;
            }
            ThinkingContent thinkingContent = (ThinkingContent) partial.getContents().get(contentIndex);
            partial.getContents().set(contentIndex, ThinkingContent.builder()
                    .thinking(nullToEmpty(thinkingContent.getThinking()) + nullToEmpty(delta.delta()))
                    .build());
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.THINKING_DELTA)
                    .itemId(delta.itemId())
                    .contentIndex(contentIndex)
                    .delta(delta.delta())
                    .partial(copyMessage(partial))
                    .build();
        }

        if (event.functionCallArgumentsDelta().isPresent()) {
            var delta = event.functionCallArgumentsDelta().get();
            Integer contentIndex = contentIndexesByItemId.get(delta.itemId());
            if (contentIndex == null) {
                return null;
            }
            ToolCallContent toolCallContent = (ToolCallContent) partial.getContents().get(contentIndex);
            String partialJson = partialToolArguments.getOrDefault(delta.itemId(), "") + nullToEmpty(delta.delta());
            partialToolArguments.put(delta.itemId(), partialJson);
            ToolCallContent updatedToolCall = ToolCallContent.builder()
                    .toolCallId(toolCallContent.getToolCallId())
                    .toolName(toolCallContent.getToolName())
                    .argumentsJson(partialJson)
                    .arguments(parseStreamingJson(partialJson))
                    .build();
            partial.getContents().set(contentIndex, updatedToolCall);
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TOOLCALL_DELTA)
                    .itemId(delta.itemId())
                    .toolCallId(updatedToolCall.getToolCallId())
                    .toolName(updatedToolCall.getToolName())
                    .contentIndex(contentIndex)
                    .delta(delta.delta())
                    .toolCall(copyToolCall(updatedToolCall))
                    .partial(copyMessage(partial))
                    .build();
        }

        if (event.functionCallArgumentsDone().isPresent()) {
            var done = event.functionCallArgumentsDone().get();
            Integer contentIndex = contentIndexesByItemId.get(done.itemId());
            if (contentIndex == null) {
                return null;
            }
            ToolCallContent toolCallContent = (ToolCallContent) partial.getContents().get(contentIndex);
            partialToolArguments.put(done.itemId(), done.arguments());
            partial.getContents().set(contentIndex, ToolCallContent.builder()
                    .toolCallId(toolCallContent.getToolCallId())
                    .toolName(toolCallContent.getToolName())
                    .argumentsJson(done.arguments())
                    .arguments(parseStreamingJson(done.arguments()))
                    .build());
            return null;
        }

        if (event.outputItemDone().isPresent()) {
            var done = event.outputItemDone().get();
            if (done.item().isReasoning()) {
                ResponseReasoningItem reasoningItem = done.item().asReasoning();
                Integer contentIndex = contentIndexesByItemId.get(reasoningItem.id());
                if (contentIndex == null) {
                    return null;
                }
                String thinking = extractReasoningSummary(reasoningItem);
                partial.getContents().set(contentIndex, ThinkingContent.builder()
                        .thinking(thinking)
                        .build());
                OpenAiReplayData.ReplayItem replayItem = replayCodec.replayItem(OpenAiReplayData.Type.REASONING, reasoningItem);
                addReplayItem(replayItem);
                return AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.THINKING_END)
                        .itemId(reasoningItem.id())
                        .contentIndex(contentIndex)
                        .content(thinking)
                        .providerState(replayCodec.providerState(partial.getResponseId(), replayItem))
                        .partial(copyMessage(partial))
                        .build();
            }
            if (done.item().isMessage()) {
                ResponseOutputMessage messageItem = done.item().asMessage();
                Integer contentIndex = contentIndexesByItemId.get(messageItem.id());
                if (contentIndex == null) {
                    return null;
                }
                String text = extractMessageText(messageItem);
                partial.getContents().set(contentIndex, TextContent.builder()
                        .text(text)
                        .build());
                OpenAiReplayData.ReplayItem replayItem = replayCodec.replayItem(OpenAiReplayData.Type.OUTPUT_MESSAGE, messageItem);
                addReplayItem(replayItem);
                return AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.TEXT_END)
                        .itemId(messageItem.id())
                        .contentIndex(contentIndex)
                        .content(text)
                        .providerState(replayCodec.providerState(partial.getResponseId(), replayItem))
                        .partial(copyMessage(partial))
                        .build();
            }
            if (done.item().isFunctionCall()) {
                var functionCall = done.item().asFunctionCall();
                String itemId = functionCall.id().orElse(functionCall.callId());
                Integer contentIndex = contentIndexesByItemId.get(itemId);
                if (contentIndex == null) {
                    return null;
                }
                String finalArguments = partialToolArguments.getOrDefault(itemId, functionCall.arguments());
                ToolCallContent toolCall = ToolCallContent.builder()
                        .toolCallId(functionCall.callId())
                        .toolName(functionCall.name())
                        .argumentsJson(finalArguments)
                        .arguments(parseStreamingJson(finalArguments))
                        .build();
                partial.getContents().set(contentIndex, toolCall);
                partialToolArguments.remove(itemId);
                OpenAiReplayData.ReplayItem replayItem = replayCodec.replayItem(OpenAiReplayData.Type.FUNCTION_CALL, functionCall);
                addReplayItem(replayItem);
                return AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.TOOLCALL_END)
                        .itemId(itemId)
                        .toolCallId(toolCall.getToolCallId())
                        .toolName(toolCall.getToolName())
                        .contentIndex(contentIndex)
                        .toolCall(copyToolCall(toolCall))
                        .providerState(replayCodec.providerState(partial.getResponseId(), replayItem))
                        .partial(copyMessage(partial))
                        .build();
            }
            return null;
        }

        if (event.completed().isPresent()) {
            Response response = event.completed().get().response();
            AssistantMessage finalMessage = finalizeMessage(
                    resolveCompletedStopReason(),
                    toUsage(response),
                    response.error().map(Object::toString).orElse(null)
            );
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.DONE)
                    .reason(toReasonString(finalMessage.getStopReason()))
                    .message(finalMessage)
                    .build();
        }

        if (event.incomplete().isPresent()) {
            Response response = event.incomplete().get().response();
            AssistantMessage.StopReason stopReason = resolveIncompleteStopReason(response);
            String reason = incompleteReason(response);
            AssistantMessage finalMessage = finalizeMessage(
                    stopReason,
                    toUsage(response),
                    "Response incomplete" + (reason == null ? "" : ": " + reason),
                    stopReason == AssistantMessage.StopReason.ERROR
                            ? ModelErrorInfo.of(ModelErrorCode.fromIncompleteReason(reason), "Response incomplete")
                            : null
            );
            if (stopReason == AssistantMessage.StopReason.LENGTH) {
                return AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.DONE)
                        .reason(toReasonString(stopReason))
                        .message(finalMessage)
                        .build();
            }
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.ERROR)
                    .reason(toReasonString(stopReason))
                    .error(finalMessage)
                    .build();
        }

        if (event.failed().isPresent()) {
            Response response = event.failed().get().response();
            ModelErrorInfo errorInfo = response.error()
                    .map(this::responseErrorInfo)
                    .orElseGet(() -> ModelErrorInfo.of(ModelErrorCode.INVALID_REQUEST, "Response failed"));
            AssistantMessage finalMessage = finalizeMessage(
                    AssistantMessage.StopReason.ERROR,
                    toUsage(response),
                    errorInfo.message(),
                    errorInfo
            );
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.ERROR)
                    .reason("error")
                    .error(finalMessage)
                    .build();
        }

        if (event.error().isPresent()) {
            var error = event.error().get();
            ModelErrorInfo errorInfo = ModelErrorInfo.of(
                    ModelErrorCode.fromResponseError(null, error.message()),
                    error.message()
            );
            AssistantMessage errorMessage = finalizeMessage(
                    AssistantMessage.StopReason.ERROR,
                    null,
                    error.message(),
                    errorInfo
            );
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.ERROR)
                    .reason("error")
                    .error(errorMessage)
                    .build();
        }

        return null;
    }

    AssistantStreamEvent unexpectedEnd() {
        String message = streamDisconnectedMessage("OpenAI stream ended unexpectedly");
        AssistantMessage errorMessage = finalizeMessage(
                AssistantMessage.StopReason.ERROR,
                null,
                message,
                ModelErrorInfo.of(ModelErrorCode.STREAM_DISCONNECTED, message)
        );
        return AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.ERROR)
                .reason("error")
                .error(errorMessage)
                .build();
    }

    AssistantStreamEvent error(RuntimeException error) {
        ModelErrorInfo errorInfo = streamErrorInfo(error);
        String message = errorInfo.message();
        AssistantMessage errorMessage = finalizeMessage(
                AssistantMessage.StopReason.ERROR,
                null,
                message,
                errorInfo
        );
        return AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.ERROR)
                .reason("error")
                .error(errorMessage)
                .build();
    }

    private String streamDisconnectedMessage(String detail) {
        return "stream disconnected before completion: " + detail;
    }

    private String errorMessage(RuntimeException error) {
        if (error == null) {
            return "unknown stream failure";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private ModelErrorInfo streamErrorInfo(RuntimeException error) {
        if (error instanceof OpenAIException) {
            ModelErrorInfo sdkError = ModelErrorInfo.fromOpenAiException(error);
            if (sdkError.code() == ModelErrorCode.RATE_LIMIT) {
                return sdkError;
            }
        }
        String message = streamDisconnectedMessage(errorMessage(error));
        return ModelErrorInfo.of(ModelErrorCode.STREAM_DISCONNECTED, message);
    }

    private int appendContent(MessageContent content) {
        partial.getContents().add(content);
        return partial.getContents().size() - 1;
    }

    private AssistantMessage finalizeMessage(
            AssistantMessage.StopReason stopReason,
            Map<String, Object> usage,
            String errorMessage
    ) {
        return finalizeMessage(stopReason, usage, errorMessage, null);
    }

    private AssistantMessage finalizeMessage(
            AssistantMessage.StopReason stopReason,
            Map<String, Object> usage,
            String errorMessage,
            ModelErrorInfo errorInfo
    ) {
        return AssistantMessage.builder()
                .timestamp(partial.getTimestamp())
                .responseId(partial.getResponseId())
                .provider(partial.getProvider())
                .model(partial.getModel())
                .contents(copyContents(partial.getContents()))
                .stopReason(stopReason)
                .usage(usage == null ? partial.getUsage() : usage)
                .errorMessage(errorMessage)
                .errorInfo(errorInfo)
                .providerState(replayCodec.providerState(partial.getResponseId(), replayItems))
                .build();
    }

    private AssistantMessage copyMessage(AssistantMessage message) {
        return AssistantMessage.builder()
                .timestamp(message.getTimestamp())
                .responseId(message.getResponseId())
                .provider(message.getProvider())
                .model(message.getModel())
                .contents(copyContents(message.getContents()))
                .stopReason(message.getStopReason())
                .usage(message.getUsage() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(message.getUsage()))
                .errorMessage(message.getErrorMessage())
                .errorInfo(message.getErrorInfo())
                .providerState(message.getProviderState())
                .build();
    }

    private List<MessageContent> copyContents(List<MessageContent> contents) {
        List<MessageContent> copied = new ArrayList<>();
        for (MessageContent content : contents) {
            if (content instanceof TextContent textContent) {
                copied.add(TextContent.builder()
                        .text(textContent.getText())
                        .build());
            } else if (content instanceof ThinkingContent thinkingContent) {
                copied.add(ThinkingContent.builder()
                        .thinking(thinkingContent.getThinking())
                        .build());
            } else if (content instanceof ToolCallContent toolCallContent) {
                copied.add(copyToolCall(toolCallContent));
            } else {
                copied.add(content);
            }
        }
        return copied;
    }

    private ToolCallContent copyToolCall(ToolCallContent toolCallContent) {
        return ToolCallContent.builder()
                .toolCallId(toolCallContent.getToolCallId())
                .toolName(toolCallContent.getToolName())
                .argumentsJson(toolCallContent.getArgumentsJson())
                .arguments(toolCallContent.getArguments())
                .build();
    }

    private Map<String, Object> toUsage(Response response) {
        return response.usage()
                .map(usage -> objectMapper.convertValue(usage, new TypeReference<Map<String, Object>>() {
                }))
                .orElseGet(LinkedHashMap::new);
    }

    private AssistantMessage.StopReason resolveCompletedStopReason() {
        for (MessageContent content : partial.getContents()) {
            if (content instanceof ToolCallContent) {
                return AssistantMessage.StopReason.TOOLUSE;
            }
        }
        return AssistantMessage.StopReason.STOP;
    }

    private AssistantMessage.StopReason resolveIncompleteStopReason(Response response) {
        return response.incompleteDetails()
                .flatMap(details -> details.reason().map(reason -> switch (reason.asString()) {
                    case "max_output_tokens" -> AssistantMessage.StopReason.LENGTH;
                    case "content_filter" -> AssistantMessage.StopReason.ERROR;
                    default -> AssistantMessage.StopReason.ERROR;
                }))
                .orElse(AssistantMessage.StopReason.ERROR);
    }

    private String incompleteReason(Response response) {
        return response.incompleteDetails()
                .flatMap(details -> details.reason().map(reason -> reason.asString()))
                .orElse(null);
    }

    private ModelErrorInfo responseErrorInfo(ResponseError error) {
        String message = error.message();
        String code = error.code().asString();
        return ModelErrorInfo.of(ModelErrorCode.fromResponseError(code, message), message);
    }

    private String toReasonString(AssistantMessage.StopReason stopReason) {
        return switch (stopReason) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case TOOLUSE -> "toolUse";
            case ABORTED -> "aborted";
            case ERROR -> "error";
        };
    }

    private String extractReasoningSummary(ResponseReasoningItem reasoningItem) {
        StringBuilder summary = new StringBuilder();
        reasoningItem.summary().forEach(item -> {
            if (!summary.isEmpty()) {
                summary.append('\n');
            }
            summary.append(item.text());
        });
        return summary.toString().trim();
    }

    private String extractMessageText(ResponseOutputMessage messageItem) {
        StringBuilder text = new StringBuilder();
        messageItem.content().forEach(content -> {
            if (content.isOutputText()) {
                text.append(content.asOutputText().text());
            } else if (content.isRefusal()) {
                text.append(content.asRefusal().refusal());
            }
        });
        return text.toString();
    }

    private void addReplayItem(OpenAiReplayData.ReplayItem replayItem) {
        if (replayItem != null) {
            replayItems.add(replayItem);
        }
    }

    private JsonNode parseStreamingJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                return objectMapper.createObjectNode();
            }
            return node;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
