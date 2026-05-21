package io.github.lingjiuu.provider.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStreamEvent;
import io.github.lingjiuu.message.AssistantMessage;
import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ThinkingContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.llm.AssistantStream;
import io.github.lingjiuu.llm.AssistantStreamEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class OpenAiStreamParser {

    public AssistantStream parseStream(
            StreamResponse<ResponseStreamEvent> streamResponse,
            String model,
            String provider
    ) {
        return new ParsedAssistantStream(streamResponse, model, provider);
    }

    private static final class ParsedAssistantStream extends AssistantStream {

        private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        private final StreamResponse<ResponseStreamEvent> streamResponse;
        private final String model;
        private final String provider;
        private AssistantMessage result;

        private ParsedAssistantStream(StreamResponse<ResponseStreamEvent> streamResponse, String model, String provider) {
            this.streamResponse = streamResponse;
            this.model = model;
            this.provider = provider;
        }

        @Override
        public AssistantMessage consume(Consumer<AssistantStreamEvent> consumer) {
            AssistantMessage partial = AssistantMessage.builder()
                    .contents(new ArrayList<>())
                    .stopReason(AssistantMessage.StopReason.STOP)
                    .model(model)
                    .provider(provider)
                    .build();

            Map<String, Integer> contentIndexesByItemId = new LinkedHashMap<>();
            Map<String, String> partialToolArguments = new LinkedHashMap<>();
            List<OpenAiReplayData.ReplayItem> replayItems = new ArrayList<>();

            try {
                for (ResponseStreamEvent event : (Iterable<ResponseStreamEvent>) streamResponse.stream()::iterator) {
                    AssistantStreamEvent assistantEvent = processEvent(
                            event,
                            partial,
                            contentIndexesByItemId,
                            partialToolArguments,
                            replayItems
                    );
                    if (assistantEvent == null) {
                        continue;
                    }

                    consumer.accept(assistantEvent);

                    if (assistantEvent.getType() == AssistantStreamEvent.Type.DONE) {
                        result = assistantEvent.getMessage();
                        return result;
                    }
                    if (assistantEvent.getType() == AssistantStreamEvent.Type.ERROR) {
                        result = assistantEvent.getError();
                        return result;
                    }
                }

                result = finalizeMessage(
                        partial,
                        AssistantMessage.StopReason.ERROR,
                        null,
                        "OpenAI stream ended unexpectedly",
                        replayItems
                );
                consumer.accept(AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.ERROR)
                        .reason("error")
                        .error(result)
                        .build());
                return result;
            } catch (RuntimeException e) {
                result = finalizeMessage(
                        partial,
                        AssistantMessage.StopReason.ERROR,
                        null,
                        e.getMessage(),
                        replayItems
                );
                consumer.accept(AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.ERROR)
                        .reason("error")
                        .error(result)
                        .build());
                return result;
            }
        }

        @Override
        public AssistantMessage result() {
            return result;
        }

        @Override
        public void close() throws IOException {
            streamResponse.close();
        }

        private AssistantStreamEvent processEvent(
                ResponseStreamEvent event,
                AssistantMessage partial,
                Map<String, Integer> contentIndexesByItemId,
                Map<String, String> partialToolArguments,
                List<OpenAiReplayData.ReplayItem> replayItems
        ) {
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
                    int contentIndex = appendContent(partial, ThinkingContent.builder().thinking("").build());
                    contentIndexesByItemId.put(item.id(), contentIndex);
                    return AssistantStreamEvent.builder()
                            .type(AssistantStreamEvent.Type.THINKING_START)
                            .contentIndex(contentIndex)
                            .partial(copyMessage(partial))
                            .build();
                }
                if (added.item().isMessage()) {
                    var item = added.item().asMessage();
                    int contentIndex = appendContent(partial, TextContent.builder().text("").build());
                    contentIndexesByItemId.put(item.id(), contentIndex);
                    return AssistantStreamEvent.builder()
                            .type(AssistantStreamEvent.Type.TEXT_START)
                            .contentIndex(contentIndex)
                            .partial(copyMessage(partial))
                            .build();
                }
                if (added.item().isFunctionCall()) {
                    var item = added.item().asFunctionCall();
                    String itemId = item.id().orElse(item.callId());
                    int contentIndex = appendContent(partial, ToolCallContent.builder()
                            .toolCallId(item.callId())
                            .toolName(item.name())
                            .argumentsJson(item.arguments())
                            .arguments(parseStreamingJson(item.arguments()))
                            .build());
                    contentIndexesByItemId.put(itemId, contentIndex);
                    partialToolArguments.put(itemId, nullToEmpty(item.arguments()));
                    return AssistantStreamEvent.builder()
                            .type(AssistantStreamEvent.Type.TOOLCALL_START)
                            .contentIndex(contentIndex)
                            .partial(copyMessage(partial))
                            .build();
                }
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
                partial.getContents().set(contentIndex, ToolCallContent.builder()
                        .toolCallId(toolCallContent.getToolCallId())
                        .toolName(toolCallContent.getToolName())
                        .argumentsJson(partialJson)
                        .arguments(parseStreamingJson(partialJson))
                        .build());
                return AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.TOOLCALL_DELTA)
                        .contentIndex(contentIndex)
                        .delta(delta.delta())
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
                    OpenAiReplayData.ReplayItem replayItem = replayItem(OpenAiReplayData.Type.REASONING, reasoningItem);
                    addReplayItem(replayItems, replayItem);
                    return AssistantStreamEvent.builder()
                            .type(AssistantStreamEvent.Type.THINKING_END)
                            .contentIndex(contentIndex)
                            .content(thinking)
                            .providerState(itemProviderState(partial, replayItem))
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
                    OpenAiReplayData.ReplayItem replayItem = replayItem(OpenAiReplayData.Type.OUTPUT_MESSAGE, messageItem);
                    addReplayItem(replayItems, replayItem);
                    return AssistantStreamEvent.builder()
                            .type(AssistantStreamEvent.Type.TEXT_END)
                            .contentIndex(contentIndex)
                            .content(text)
                            .providerState(itemProviderState(partial, replayItem))
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
                    OpenAiReplayData.ReplayItem replayItem = replayItem(OpenAiReplayData.Type.FUNCTION_CALL, functionCall);
                    addReplayItem(replayItems, replayItem);
                    return AssistantStreamEvent.builder()
                            .type(AssistantStreamEvent.Type.TOOLCALL_END)
                            .contentIndex(contentIndex)
                            .toolCall(copyToolCall(toolCall))
                            .providerState(itemProviderState(partial, replayItem))
                            .partial(copyMessage(partial))
                            .build();
                }
            }

            if (event.completed().isPresent()) {
                Response response = event.completed().get().response();
                AssistantMessage finalMessage = finalizeMessage(
                        partial,
                        resolveCompletedStopReason(partial),
                        toUsage(response),
                        response.error().map(Object::toString).orElse(null),
                        replayItems
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
                AssistantMessage finalMessage = finalizeMessage(
                        partial,
                        stopReason,
                        toUsage(response),
                        "Response incomplete",
                        replayItems
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
                AssistantMessage finalMessage = finalizeMessage(
                        partial,
                        AssistantMessage.StopReason.ERROR,
                        toUsage(response),
                        response.error().map(Object::toString).orElse("Response failed"),
                        replayItems
                );
                return AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.ERROR)
                        .reason("error")
                        .error(finalMessage)
                        .build();
            }

            if (event.error().isPresent()) {
                var error = event.error().get();
                AssistantMessage errorMessage = finalizeMessage(
                        partial,
                        AssistantMessage.StopReason.ERROR,
                        null,
                        error.message(),
                        replayItems
                );
                return AssistantStreamEvent.builder()
                        .type(AssistantStreamEvent.Type.ERROR)
                        .reason("error")
                        .error(errorMessage)
                        .build();
            }

            return null;
        }

        private int appendContent(AssistantMessage message, MessageContent content) {
            message.getContents().add(content);
            return message.getContents().size() - 1;
        }

        private AssistantMessage finalizeMessage(
                AssistantMessage partial,
                AssistantMessage.StopReason stopReason,
                Map<String, Object> usage,
                String errorMessage,
                List<OpenAiReplayData.ReplayItem> replayItems
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
                    .providerState(OpenAiReplayData.builder()
                            .responseId(partial.getResponseId())
                            .items(new ArrayList<>(replayItems))
                            .build())
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
                    .usage(new LinkedHashMap<>(message.getUsage()))
                    .errorMessage(message.getErrorMessage())
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

        private AssistantMessage.StopReason resolveCompletedStopReason(AssistantMessage partial) {
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

        private OpenAiReplayData.ReplayItem replayItem(OpenAiReplayData.Type type, Object value) {
            try {
                String json = objectMapper.writeValueAsString(value);
                if (type == OpenAiReplayData.Type.FUNCTION_CALL) {
                    json = sanitizeFunctionCallReplayJson(json);
                }
                return OpenAiReplayData.ReplayItem.builder()
                        .type(type)
                        .json(json)
                        .build();
            } catch (Exception e) {
                return null;
            }
        }

        private void addReplayItem(List<OpenAiReplayData.ReplayItem> replayItems, OpenAiReplayData.ReplayItem replayItem) {
            if (replayItem != null) {
                replayItems.add(replayItem);
            }
        }

        private OpenAiReplayData itemProviderState(
                AssistantMessage partial,
                OpenAiReplayData.ReplayItem replayItem
        ) {
            if (replayItem == null) {
                return null;
            }
            return OpenAiReplayData.builder()
                    .responseId(partial == null ? null : partial.getResponseId())
                    .items(List.of(replayItem))
                    .build();
        }

        private String sanitizeFunctionCallReplayJson(String json) throws Exception {
            ObjectNode node = (ObjectNode) objectMapper.readTree(json);
            node.remove("isValid");
            return objectMapper.writeValueAsString(node);
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
}
