package io.github.lingjiuu.transport.stdio;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.transport.JsonRpcError;
import io.github.lingjiuu.transport.JsonRpcErrorResponse;
import io.github.lingjiuu.transport.JsonRpcNotification;
import io.github.lingjiuu.transport.JsonRpcResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

class StdioJsonWriter {

    private final ObjectMapper objectMapper;
    private final Writer writer;

    StdioJsonWriter(ObjectMapper objectMapper, Writer writer) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        if (writer == null) {
            throw new IllegalArgumentException("writer must not be null");
        }
        this.objectMapper = objectMapper;
        this.writer = writer;
    }

    synchronized void writeResponse(com.fasterxml.jackson.databind.JsonNode id, Object result) {
        write(new JsonRpcResponse(id, result));
    }

    synchronized void writeError(com.fasterxml.jackson.databind.JsonNode id, long code, String message, Object data) {
        write(new JsonRpcErrorResponse(id, new JsonRpcError(code, message, data)));
    }

    synchronized void writeNotification(String method, Object params) {
        write(new JsonRpcNotification(method, params));
    }

    synchronized void flush() {
        try {
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(Object message) {
        try {
            writer.write(objectMapper.writeValueAsString(message));
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
