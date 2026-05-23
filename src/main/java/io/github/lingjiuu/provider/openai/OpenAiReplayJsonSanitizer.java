package io.github.lingjiuu.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;

final class OpenAiReplayJsonSanitizer {

    private OpenAiReplayJsonSanitizer() {
    }

    static String sanitize(String json, ObjectMapper objectMapper) throws Exception {
        if (json == null || json.isBlank()) {
            return json;
        }
        JsonNode node = objectMapper.readTree(json);
        removeSdkOnlyFields(node);
        return objectMapper.writeValueAsString(node);
    }

    private static void removeSdkOnlyFields(JsonNode node) {
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
