package io.github.lingjiuu.transport;

import com.fasterxml.jackson.databind.JsonNode;

public record JsonRpcErrorResponse(
        JsonNode id,
        JsonRpcError error
) {
}
