package io.github.lingjiuu.wire.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionToolCall;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class OpenAiStreamEventMapper {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final OpenAiReplayCodec replayCodec = new OpenAiReplayCodec(objectMapper);
    private final Map<String, Integer> contentIndexesByItemId = new LinkedHashMap<>();
    private final Map<String, String> partialToolArguments = new LinkedHashMap<>();
    private final List<OpenAiReplayData.ReplayItem> replayItems = new ArrayList<>();
    private final Set<String> completedItemIds = new LinkedHashSet<>();
    private final Deque<AssistantStreamEvent> pendingEvents = new ArrayDeque<>();
    private final String provider;
    private final AssistantMessage partial;
    private String toolBatchId;

    OpenAiStreamEventMapper(String model, String provider) {
        this.provider = provider;
        this.partial = AssistantMessage.builder()
                .contents(new ArrayList<>())
                .stopReason(AssistantMessage.StopReason.STOP)
                .model(model)
                .provider(provider)
                .build();
    }

    List<AssistantStreamEvent> mapAll(ResponseStreamEvent event) {
        List<AssistantStreamEvent> events = new ArrayList<>();
        AssistantStreamEvent mapped = map(event);
        if (mapped != null) {
            events.add(mapped);
        }
        events.addAll(drainPending());
        return events;
    }

    List<AssistantStreamEvent> drainPending() {
        List<AssistantStreamEvent> events = new ArrayList<>();
        while (!pendingEvents.isEmpty()) {
            events.add(pendingEvents.removeFirst());
        }
        return events;
    }

    AssistantStreamEvent map(ResponseStreamEvent event) {
        if (event.created().isPresent()) {
            Response response = event.created().get().response();
            partial.setResponseId(response.id());
            if (isBlank(toolBatchId)) {
                toolBatchId = response.id();
            }
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
                        .toolBatchId(currentToolBatchId())
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
                    .toolBatchId(toolCallContent.getToolBatchId())
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
                    .toolBatchId(toolCallContent.getToolBatchId())
                    .toolName(toolCallContent.getToolName())
                    .argumentsJson(done.arguments())
                    .arguments(parseStreamingJson(done.arguments()))
                    .build());
            return null;
        }

        if (event.outputItemDone().isPresent()) {
            var done = event.outputItemDone().get();
            if (done.item().isReasoning()) {
                return completeReasoningItem(done.item().asReasoning());
            }
            if (done.item().isMessage()) {
                return completeMessageItem(done.item().asMessage());
            }
            if (done.item().isFunctionCall()) {
                return completeFunctionCall(done.item().asFunctionCall());
            }
            return null;
        }

        if (event.completed().isPresent()) {
            Response response = event.completed().get().response();
            enqueueCompletedOutputItems(response);
            AssistantMessage finalMessage = finalizeMessage(
                    resolveCompletedStopReason(),
                    toUsage(response),
                    response.error().map(Object::toString).orElse(null)
            );
            AssistantStreamEvent done = AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.DONE)
                    .reason(toReasonString(finalMessage.getStopReason()))
                    .message(finalMessage)
                    .build();
            return maybeDeferDone(done);
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

        return mapRaw(event);
    }

    private AssistantStreamEvent mapRaw(ResponseStreamEvent event) {
        JsonNode raw = rawEventJson(event);
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            return null;
        }
        String type = text(raw, "type");
        return switch (type) {
            case "response.created" -> rawCreated(raw);
            case "response.output_item.added" -> rawOutputItemAdded(raw);
            case "response.output_text.delta" -> rawOutputTextDelta(raw);
            case "response.reasoning_summary_text.delta" -> rawReasoningSummaryTextDelta(raw);
            case "response.function_call_arguments.delta" -> rawFunctionCallArgumentsDelta(raw);
            case "response.function_call_arguments.done" -> rawFunctionCallArgumentsDone(raw);
            case "response.output_item.done" -> rawOutputItemDone(raw);
            case "response.completed" -> rawCompleted(raw);
            case "response.incomplete" -> rawIncomplete(raw);
            case "response.failed" -> rawFailed(raw);
            case "error", "response.error" -> rawError(raw);
            default -> null;
        };
    }

    private AssistantStreamEvent rawCreated(JsonNode raw) {
        JsonNode response = raw.path("response");
        String responseId = text(response, "id");
        if (!isBlank(responseId)) {
            partial.setResponseId(responseId);
            if (isBlank(toolBatchId)) {
                toolBatchId = responseId;
            }
        }
        partial.setProvider(provider);
        String model = text(response, "model");
        if (!isBlank(model)) {
            partial.setModel(model);
        }
        return AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.START)
                .partial(copyMessage(partial))
                .build();
    }

    private AssistantStreamEvent rawOutputItemAdded(JsonNode raw) {
        JsonNode item = raw.path("item");
        String itemType = text(item, "type");
        if ("reasoning".equals(itemType)) {
            String itemId = text(item, "id");
            int contentIndex = appendContent(ThinkingContent.builder().thinking("").build());
            if (!isBlank(itemId)) {
                contentIndexesByItemId.put(itemId, contentIndex);
            }
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.THINKING_START)
                    .itemId(itemId)
                    .contentIndex(contentIndex)
                    .partial(copyMessage(partial))
                    .build();
        }
        if ("message".equals(itemType)) {
            String itemId = text(item, "id");
            int contentIndex = appendContent(TextContent.builder().text("").build());
            if (!isBlank(itemId)) {
                contentIndexesByItemId.put(itemId, contentIndex);
            }
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_START)
                    .itemId(itemId)
                    .contentIndex(contentIndex)
                    .partial(copyMessage(partial))
                    .build();
        }
        if ("function_call".equals(itemType)) {
            String itemId = firstText(item, "id", "call_id", "callId");
            ToolCallContent toolCall = ToolCallContent.builder()
                    .toolCallId(firstText(item, "call_id", "callId", "id"))
                    .toolBatchId(currentToolBatchId())
                    .toolName(text(item, "name"))
                    .argumentsJson(nullToEmpty(text(item, "arguments")))
                    .arguments(parseStreamingJson(text(item, "arguments")))
                    .build();
            int contentIndex = appendContent(toolCall);
            if (!isBlank(itemId)) {
                contentIndexesByItemId.put(itemId, contentIndex);
                partialToolArguments.put(itemId, nullToEmpty(text(item, "arguments")));
            }
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TOOLCALL_START)
                    .itemId(itemId)
                    .toolCallId(toolCall.getToolCallId())
                    .toolName(toolCall.getToolName())
                    .contentIndex(contentIndex)
                    .toolCall(copyToolCall(toolCall))
                    .partial(copyMessage(partial))
                    .build();
        }
        return null;
    }

    private AssistantStreamEvent rawOutputTextDelta(JsonNode raw) {
        String itemId = firstText(raw, "item_id", "itemId");
        int contentIndex = ensureTextContent(itemId, integer(raw, "content_index"));
        TextContent textContent = (TextContent) partial.getContents().get(contentIndex);
        String delta = nullToEmpty(text(raw, "delta"));
        partial.getContents().set(contentIndex, TextContent.builder()
                .text(nullToEmpty(textContent.getText()) + delta)
                .build());
        return AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.TEXT_DELTA)
                .itemId(itemId)
                .contentIndex(contentIndex)
                .delta(delta)
                .partial(copyMessage(partial))
                .build();
    }

    private AssistantStreamEvent rawReasoningSummaryTextDelta(JsonNode raw) {
        String itemId = firstText(raw, "item_id", "itemId");
        int contentIndex = ensureThinkingContent(itemId, integer(raw, "content_index"));
        ThinkingContent thinkingContent = (ThinkingContent) partial.getContents().get(contentIndex);
        String delta = nullToEmpty(text(raw, "delta"));
        partial.getContents().set(contentIndex, ThinkingContent.builder()
                .thinking(nullToEmpty(thinkingContent.getThinking()) + delta)
                .build());
        return AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.THINKING_DELTA)
                .itemId(itemId)
                .contentIndex(contentIndex)
                .delta(delta)
                .partial(copyMessage(partial))
                .build();
    }

    private AssistantStreamEvent rawFunctionCallArgumentsDelta(JsonNode raw) {
        String itemId = firstText(raw, "item_id", "itemId");
        Integer contentIndex = contentIndexesByItemId.get(itemId);
        if (!validContentIndex(contentIndex) || !(partial.getContents().get(contentIndex) instanceof ToolCallContent toolCallContent)) {
            return null;
        }
        String partialJson = partialToolArguments.getOrDefault(itemId, "") + nullToEmpty(text(raw, "delta"));
        partialToolArguments.put(itemId, partialJson);
        ToolCallContent updatedToolCall = ToolCallContent.builder()
                .toolCallId(toolCallContent.getToolCallId())
                .toolBatchId(toolCallContent.getToolBatchId())
                .toolName(toolCallContent.getToolName())
                .argumentsJson(partialJson)
                .arguments(parseStreamingJson(partialJson))
                .build();
        partial.getContents().set(contentIndex, updatedToolCall);
        return AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.TOOLCALL_DELTA)
                .itemId(itemId)
                .toolCallId(updatedToolCall.getToolCallId())
                .toolName(updatedToolCall.getToolName())
                .contentIndex(contentIndex)
                .delta(text(raw, "delta"))
                .toolCall(copyToolCall(updatedToolCall))
                .partial(copyMessage(partial))
                .build();
    }

    private AssistantStreamEvent rawFunctionCallArgumentsDone(JsonNode raw) {
        String itemId = firstText(raw, "item_id", "itemId");
        Integer contentIndex = contentIndexesByItemId.get(itemId);
        if (!validContentIndex(contentIndex) || !(partial.getContents().get(contentIndex) instanceof ToolCallContent toolCallContent)) {
            return null;
        }
        String arguments = nullToEmpty(text(raw, "arguments"));
        partialToolArguments.put(itemId, arguments);
        partial.getContents().set(contentIndex, ToolCallContent.builder()
                .toolCallId(toolCallContent.getToolCallId())
                .toolBatchId(toolCallContent.getToolBatchId())
                .toolName(toolCallContent.getToolName())
                .argumentsJson(arguments)
                .arguments(parseStreamingJson(arguments))
                .build());
        return null;
    }

    private AssistantStreamEvent rawOutputItemDone(JsonNode raw) {
        JsonNode item = raw.path("item");
        String itemType = text(item, "type");
        if ("reasoning".equals(itemType)) {
            String itemId = text(item, "id");
            if (completedItemIds.contains(itemId)) {
                return null;
            }
            int contentIndex = ensureThinkingContent(itemId, integer(raw, "content_index"));
            String thinking = extractReasoningSummary(item);
            partial.getContents().set(contentIndex, ThinkingContent.builder()
                    .thinking(thinking)
                    .build());
            OpenAiReplayData.ReplayItem replayItem = rawReplayItem(OpenAiReplayData.Type.REASONING, item);
            addReplayItem(replayItem);
            completedItemIds.add(itemId);
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.THINKING_END)
                    .itemId(itemId)
                    .contentIndex(contentIndex)
                    .content(thinking)
                    .providerState(replayCodec.providerState(partial.getResponseId(), replayItem))
                    .partial(copyMessage(partial))
                    .build();
        }
        if ("message".equals(itemType)) {
            String itemId = text(item, "id");
            if (completedItemIds.contains(itemId)) {
                return null;
            }
            int contentIndex = ensureTextContent(itemId, integer(raw, "content_index"));
            String text = extractMessageText(item);
            partial.getContents().set(contentIndex, TextContent.builder()
                    .text(text)
                    .build());
            OpenAiReplayData.ReplayItem replayItem = rawReplayItem(OpenAiReplayData.Type.OUTPUT_MESSAGE, item);
            addReplayItem(replayItem);
            completedItemIds.add(itemId);
            return AssistantStreamEvent.builder()
                    .type(AssistantStreamEvent.Type.TEXT_END)
                    .itemId(itemId)
                    .contentIndex(contentIndex)
                    .content(text)
                    .providerState(replayCodec.providerState(partial.getResponseId(), replayItem))
                    .partial(copyMessage(partial))
                    .build();
        }
        if ("function_call".equals(itemType)) {
            String itemId = firstText(item, "id", "call_id", "callId");
            if (completedItemIds.contains(itemId)) {
                return null;
            }
            Integer contentIndex = contentIndexesByItemId.get(itemId);
            if (!validContentIndex(contentIndex) || !(partial.getContents().get(contentIndex) instanceof ToolCallContent existingToolCall)) {
                return null;
            }
            String finalArguments = partialToolArguments.getOrDefault(itemId, text(item, "arguments"));
            ToolCallContent toolCall = ToolCallContent.builder()
                    .toolCallId(firstText(item, "call_id", "callId", "id"))
                    .toolBatchId(isBlank(existingToolCall.getToolBatchId())
                            ? currentToolBatchId()
                            : existingToolCall.getToolBatchId())
                    .toolName(text(item, "name"))
                    .argumentsJson(finalArguments)
                    .arguments(parseStreamingJson(finalArguments))
                    .build();
            partial.getContents().set(contentIndex, toolCall);
            partialToolArguments.remove(itemId);
            OpenAiReplayData.ReplayItem replayItem = rawReplayItem(OpenAiReplayData.Type.FUNCTION_CALL, item);
            addReplayItem(replayItem);
            completedItemIds.add(itemId);
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

    private AssistantStreamEvent rawCompleted(JsonNode raw) {
        JsonNode response = raw.path("response");
        applyResponseIdentity(response);
        enqueueRawCompletedOutputItems(response);
        AssistantMessage finalMessage = finalizeMessage(
                resolveCompletedStopReason(),
                usage(response),
                errorText(response.path("error"))
        );
        AssistantStreamEvent done = AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.DONE)
                .reason(toReasonString(finalMessage.getStopReason()))
                .message(finalMessage)
                .build();
        return maybeDeferDone(done);
    }

    private AssistantStreamEvent rawIncomplete(JsonNode raw) {
        JsonNode response = raw.path("response");
        applyResponseIdentity(response);
        String reason = text(response.path("incomplete_details"), "reason");
        AssistantMessage.StopReason stopReason = "max_output_tokens".equals(reason)
                ? AssistantMessage.StopReason.LENGTH
                : AssistantMessage.StopReason.ERROR;
        AssistantMessage finalMessage = finalizeMessage(
                stopReason,
                usage(response),
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

    private AssistantStreamEvent rawFailed(JsonNode raw) {
        JsonNode response = raw.path("response");
        applyResponseIdentity(response);
        JsonNode error = response.path("error");
        String message = errorText(error);
        ModelErrorInfo errorInfo = ModelErrorInfo.of(
                ModelErrorCode.fromResponseError(text(error, "code"), message),
                isBlank(message) ? "Response failed" : message
        );
        AssistantMessage finalMessage = finalizeMessage(
                AssistantMessage.StopReason.ERROR,
                usage(response),
                errorInfo.message(),
                errorInfo
        );
        return AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.ERROR)
                .reason("error")
                .error(finalMessage)
                .build();
    }

    private AssistantStreamEvent rawError(JsonNode raw) {
        String message = firstText(raw, "message", "error");
        if (isBlank(message) && raw.path("error").isObject()) {
            message = errorText(raw.path("error"));
        }
        ModelErrorInfo errorInfo = ModelErrorInfo.of(
                ModelErrorCode.fromResponseError(text(raw.path("error"), "code"), message),
                isBlank(message) ? "Response failed" : message
        );
        AssistantMessage errorMessage = finalizeMessage(
                AssistantMessage.StopReason.ERROR,
                null,
                errorInfo.message(),
                errorInfo
        );
        return AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.ERROR)
                .reason("error")
                .error(errorMessage)
                .build();
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

    private void enqueueCompletedOutputItems(Response response) {
        if (response == null) {
            return;
        }
        response.output().forEach(item -> {
            AssistantStreamEvent event = null;
            if (item.isReasoning()) {
                event = completeReasoningItem(item.asReasoning());
            } else if (item.isMessage()) {
                event = completeMessageItem(item.asMessage());
            } else if (item.isFunctionCall()) {
                event = completeFunctionCall(item.asFunctionCall());
            }
            if (event != null) {
                pendingEvents.addLast(event);
            }
        });
    }

    private void enqueueRawCompletedOutputItems(JsonNode response) {
        JsonNode output = response == null ? null : response.path("output");
        if (output == null || !output.isArray()) {
            return;
        }
        for (JsonNode item : output) {
            var rawDone = objectMapper.createObjectNode();
            rawDone.set("item", item);
            AssistantStreamEvent event = rawOutputItemDone(rawDone);
            if (event != null) {
                pendingEvents.addLast(event);
            }
        }
    }

    private AssistantStreamEvent completeReasoningItem(ResponseReasoningItem reasoningItem) {
        if (reasoningItem == null || completedItemIds.contains(reasoningItem.id())) {
            return null;
        }
        int contentIndex = ensureThinkingContent(reasoningItem.id(), null);
        String thinking = extractReasoningSummary(reasoningItem);
        partial.getContents().set(contentIndex, ThinkingContent.builder()
                .thinking(thinking)
                .build());
        OpenAiReplayData.ReplayItem replayItem = replayCodec.replayItem(OpenAiReplayData.Type.REASONING, reasoningItem);
        addReplayItem(replayItem);
        completedItemIds.add(reasoningItem.id());
        return AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.THINKING_END)
                .itemId(reasoningItem.id())
                .contentIndex(contentIndex)
                .content(thinking)
                .providerState(replayCodec.providerState(partial.getResponseId(), replayItem))
                .partial(copyMessage(partial))
                .build();
    }

    private AssistantStreamEvent completeMessageItem(ResponseOutputMessage messageItem) {
        if (messageItem == null || completedItemIds.contains(messageItem.id())) {
            return null;
        }
        int contentIndex = ensureTextContent(messageItem.id(), null);
        String text = extractMessageText(messageItem);
        partial.getContents().set(contentIndex, TextContent.builder()
                .text(text)
                .build());
        OpenAiReplayData.ReplayItem replayItem = replayCodec.replayItem(OpenAiReplayData.Type.OUTPUT_MESSAGE, messageItem);
        addReplayItem(replayItem);
        completedItemIds.add(messageItem.id());
        return AssistantStreamEvent.builder()
                .type(AssistantStreamEvent.Type.TEXT_END)
                .itemId(messageItem.id())
                .contentIndex(contentIndex)
                .content(text)
                .providerState(replayCodec.providerState(partial.getResponseId(), replayItem))
                .partial(copyMessage(partial))
                .build();
    }

    private AssistantStreamEvent completeFunctionCall(ResponseFunctionToolCall functionCall) {
        if (functionCall == null) {
            return null;
        }
        String itemId = functionCall.id().orElse(functionCall.callId());
        if (completedItemIds.contains(itemId)) {
            return null;
        }
        Integer contentIndex = contentIndexesByItemId.get(itemId);
        if (!validContentIndex(contentIndex) || !(partial.getContents().get(contentIndex) instanceof ToolCallContent existingToolCall)) {
            return null;
        }
        String finalArguments = partialToolArguments.getOrDefault(itemId, functionCall.arguments());
        ToolCallContent toolCall = ToolCallContent.builder()
                .toolCallId(functionCall.callId())
                .toolBatchId(isBlank(existingToolCall.getToolBatchId())
                        ? currentToolBatchId()
                        : existingToolCall.getToolBatchId())
                .toolName(functionCall.name())
                .argumentsJson(finalArguments)
                .arguments(parseStreamingJson(finalArguments))
                .build();
        partial.getContents().set(contentIndex, toolCall);
        partialToolArguments.remove(itemId);
        OpenAiReplayData.ReplayItem replayItem = replayCodec.replayItem(OpenAiReplayData.Type.FUNCTION_CALL, functionCall);
        addReplayItem(replayItem);
        completedItemIds.add(itemId);
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

    private AssistantStreamEvent maybeDeferDone(AssistantStreamEvent done) {
        if (pendingEvents.isEmpty()) {
            return done;
        }
        pendingEvents.addLast(done);
        return pendingEvents.removeFirst();
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

    private int ensureTextContent(String itemId, Integer contentIndexHint) {
        Integer contentIndex = contentIndexesByItemId.get(itemId);
        if (validContentIndex(contentIndex) && partial.getContents().get(contentIndex) instanceof TextContent) {
            return contentIndex;
        }
        if (validContentIndex(contentIndexHint) && partial.getContents().get(contentIndexHint) instanceof TextContent) {
            if (!isBlank(itemId)) {
                contentIndexesByItemId.put(itemId, contentIndexHint);
            }
            return contentIndexHint;
        }
        int newIndex = appendContent(TextContent.builder().text("").build());
        if (!isBlank(itemId)) {
            contentIndexesByItemId.put(itemId, newIndex);
        }
        return newIndex;
    }

    private int ensureThinkingContent(String itemId, Integer contentIndexHint) {
        Integer contentIndex = contentIndexesByItemId.get(itemId);
        if (validContentIndex(contentIndex) && partial.getContents().get(contentIndex) instanceof ThinkingContent) {
            return contentIndex;
        }
        if (validContentIndex(contentIndexHint) && partial.getContents().get(contentIndexHint) instanceof ThinkingContent) {
            if (!isBlank(itemId)) {
                contentIndexesByItemId.put(itemId, contentIndexHint);
            }
            return contentIndexHint;
        }
        int newIndex = appendContent(ThinkingContent.builder().thinking("").build());
        if (!isBlank(itemId)) {
            contentIndexesByItemId.put(itemId, newIndex);
        }
        return newIndex;
    }

    private boolean validContentIndex(Integer contentIndex) {
        return contentIndex != null && contentIndex >= 0 && contentIndex < partial.getContents().size();
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
        if (stopReason == AssistantMessage.StopReason.STOP
                && errorInfo == null
                && isBlank(errorMessage)
                && !hasVisibleOutput()) {
            String message = "Model completed without visible assistant output.";
            stopReason = AssistantMessage.StopReason.ERROR;
            errorMessage = message;
            errorInfo = ModelErrorInfo.of(ModelErrorCode.INVALID_REQUEST, message);
        }
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

    private boolean hasVisibleOutput() {
        for (MessageContent content : partial.getContents()) {
            if (content instanceof TextContent textContent && !isBlank(textContent.getText())) {
                return true;
            }
            if (content instanceof ToolCallContent) {
                return true;
            }
        }
        return false;
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
                .toolBatchId(toolCallContent.getToolBatchId())
                .toolName(toolCallContent.getToolName())
                .argumentsJson(toolCallContent.getArgumentsJson())
                .arguments(toolCallContent.getArguments())
                .build();
    }

    private String currentToolBatchId() {
        if (isBlank(toolBatchId)) {
            toolBatchId = isBlank(partial.getResponseId())
                    ? "local-tool-batch-" + UUID.randomUUID()
                    : partial.getResponseId();
        }
        return toolBatchId;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
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

    private String extractReasoningSummary(JsonNode reasoningItem) {
        StringBuilder summary = new StringBuilder();
        JsonNode summaries = reasoningItem.path("summary");
        if (summaries.isArray()) {
            for (JsonNode item : summaries) {
                String text = text(item, "text");
                if (isBlank(text)) {
                    continue;
                }
                if (!summary.isEmpty()) {
                    summary.append('\n');
                }
                summary.append(text);
            }
        }
        return summary.toString().trim();
    }

    private String extractMessageText(JsonNode messageItem) {
        StringBuilder text = new StringBuilder();
        JsonNode contents = messageItem.path("content");
        if (contents.isArray()) {
            for (JsonNode content : contents) {
                String type = text(content, "type");
                if ("output_text".equals(type)) {
                    text.append(text(content, "text"));
                } else if ("refusal".equals(type)) {
                    text.append(text(content, "refusal"));
                }
            }
        }
        return text.toString();
    }

    private JsonNode rawEventJson(ResponseStreamEvent event) {
        if (event == null || event._json().isEmpty()) {
            return null;
        }
        JsonValue json = event._json().get();
        try {
            Object converted = json.convert(JsonNode.class);
            if (converted instanceof JsonNode node) {
                return node;
            }
        } catch (RuntimeException ignored) {
        }
        try {
            return objectMapper.valueToTree(json.convert(Object.class));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void applyResponseIdentity(JsonNode response) {
        if (response == null || response.isMissingNode() || response.isNull()) {
            return;
        }
        String responseId = text(response, "id");
        if (!isBlank(responseId)) {
            partial.setResponseId(responseId);
            if (isBlank(toolBatchId)) {
                toolBatchId = responseId;
            }
        }
        String model = text(response, "model");
        if (!isBlank(model)) {
            partial.setModel(model);
        }
        partial.setProvider(provider);
    }

    private Map<String, Object> usage(JsonNode response) {
        JsonNode usage = response == null ? null : response.path("usage");
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(usage, new TypeReference<Map<String, Object>>() {
        });
    }

    private OpenAiReplayData.ReplayItem rawReplayItem(OpenAiReplayData.Type type, JsonNode item) {
        if (item == null || item.isMissingNode() || item.isNull()) {
            return null;
        }
        try {
            return OpenAiReplayData.ReplayItem.builder()
                    .type(type)
                    .json(replayCodec.sanitize(objectMapper.writeValueAsString(item)))
                    .build();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String errorText(JsonNode error) {
        if (error == null || error.isMissingNode() || error.isNull()) {
            return null;
        }
        if (error.isTextual()) {
            return error.asText();
        }
        String message = text(error, "message");
        return isBlank(message) ? error.toString() : message;
    }

    private String firstText(JsonNode node, String... fields) {
        if (fields == null) {
            return null;
        }
        for (String field : fields) {
            String text = text(node, field);
            if (!isBlank(text)) {
                return text;
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        if (node == null || field == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() && field.indexOf('_') >= 0) {
            value = node.path(snakeToCamel(field));
        }
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return null;
    }

    private Integer integer(JsonNode node, String field) {
        if (node == null || field == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() && field.indexOf('_') >= 0) {
            value = node.path(snakeToCamel(field));
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String snakeToCamel(String value) {
        StringBuilder result = new StringBuilder();
        boolean upperNext = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '_') {
                upperNext = true;
            } else if (upperNext) {
                result.append(Character.toUpperCase(ch));
                upperNext = false;
            } else {
                result.append(ch);
            }
        }
        return result.toString();
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
