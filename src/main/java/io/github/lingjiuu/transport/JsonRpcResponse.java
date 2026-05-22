package io.github.lingjiuu.transport;

import com.fasterxml.jackson.databind.JsonNode;

public record JsonRpcResponse(
        JsonNode id,
        Object result
) {
}
