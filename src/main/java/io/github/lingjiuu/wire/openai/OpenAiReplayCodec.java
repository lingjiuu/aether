package io.github.lingjiuu.wire.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class OpenAiReplayCodec {

    private final ObjectMapper objectMapper;

    OpenAiReplayCodec() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    OpenAiReplayCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<ResponseInputItem> toInputItems(OpenAiReplayData replayData) {
        List<ResponseInputItem> inputItems = new ArrayList<>();
        if (replayData == null || replayData.getItems() == null) {
            return inputItems;
        }
        for (OpenAiReplayData.ReplayItem item : replayData.getItems()) {
            ResponseInputItem inputItem = toInputItem(item);
            if (inputItem != null) {
                inputItems.add(inputItem);
            }
        }
        return inputItems;
    }

    OpenAiReplayData.ReplayItem replayItem(OpenAiReplayData.Type type, Object value) {
        try {
            return OpenAiReplayData.ReplayItem.builder()
                    .type(type)
                    .json(sanitize(objectMapper.writeValueAsString(value)))
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    OpenAiReplayData providerState(String responseId, List<OpenAiReplayData.ReplayItem> items) {
        return OpenAiReplayData.builder()
                .responseId(responseId)
                .items(items == null ? List.of() : new ArrayList<>(items))
                .build();
    }

    OpenAiReplayData providerState(String responseId, OpenAiReplayData.ReplayItem item) {
        if (item == null) {
            return null;
        }
        return providerState(responseId, List.of(item));
    }

    String sanitize(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return json;
        }
        JsonNode node = objectMapper.readTree(json);
        removeSdkOnlyFields(node);
        return objectMapper.writeValueAsString(node);
    }

    private ResponseInputItem toInputItem(OpenAiReplayData.ReplayItem item) {
        if (item == null || item.getType() == null || item.getJson() == null || item.getJson().isBlank()) {
            return null;
        }
        try {
            String json = sanitize(item.getJson());
            return switch (item.getType()) {
                case OUTPUT_MESSAGE -> ResponseInputItem.ofResponseOutputMessage(
                        objectMapper.readValue(json, ResponseOutputMessage.class)
                );
                case REASONING -> ResponseInputItem.ofReasoning(
                        objectMapper.readValue(json, ResponseReasoningItem.class)
                );
                case FUNCTION_CALL -> ResponseInputItem.ofFunctionCall(
                        objectMapper.readValue(json, ResponseFunctionToolCall.class)
                );
            };
        } catch (Exception ignored) {
            return null;
        }
    }

    private void removeSdkOnlyFields(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            objectNode.remove("isValid");
            Iterator<JsonNode> values = objectNode.elements();
            while (values.hasNext()) {
                removeSdkOnlyFields(values.next());
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                removeSdkOnlyFields(item);
            }
        }
    }
}
