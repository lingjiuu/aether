package io.github.lingjiuu.tool.render;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lingjiuu.message.MessageContents;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionResult;

import java.util.Map;
import java.util.function.Function;

public class ToolDisplayRenderer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final Function<String, ToolDefinition> toolLookup;

    public ToolDisplayRenderer(Function<String, ToolDefinition> toolLookup) {
        this.toolLookup = toolLookup == null ? ignored -> null : toolLookup;
    }

    public ToolRenderedOutput renderCall(ToolCallContent toolCall) {
        Map<String, Object> arguments = parseArguments(toolCall == null ? null : toolCall.getArgumentsJson());
        ToolRenderRequest request = ToolRenderRequest.forCall(toolCall, arguments);
        ToolDefinition definition = toolLookup.apply(request.toolName());
        ToolRenderedOutput rendered = safeRenderCall(definition, request);
        if (rendered != null) {
            return rendered;
        }
        String name = safeName(request.toolName());
        String args = request.argumentsJson() == null || request.argumentsJson().isBlank()
                ? "{}"
                : request.argumentsJson();
        return ToolRenderedOutput.text(name + " " + args);
    }

    public ToolRenderedOutput renderResult(ToolResultMessage toolResult) {
        ToolRenderRequest request = ToolRenderRequest.forResult(toolResult);
        ToolDefinition definition = toolLookup.apply(request.toolName());
        ToolRenderedOutput rendered = safeRenderResult(definition, request);
        if (rendered != null) {
            return rendered;
        }
        return ToolRenderedOutput.text(toolResult == null ? "" : MessageContents.text(toolResult));
    }

    public ToolRenderedOutput renderPartial(ToolCallContent toolCall, ToolExecutionResult partialResult) {
        ToolRenderRequest request = ToolRenderRequest.forPartial(toolCall, partialResult);
        ToolDefinition definition = toolLookup.apply(request.toolName());
        ToolRenderedOutput rendered = safeRenderResult(definition, request);
        if (rendered != null) {
            return rendered;
        }
        return ToolRenderedOutput.text(partialResult == null
                ? ""
                : MessageContents.text(ToolResultMessage.builder()
                .toolName(request.toolName())
                .toolCallId(request.toolCallId())
                .contents(partialResult.getContents())
                .isError(partialResult.isError())
                .details(partialResult.getDetails())
                .build()));
    }

    private ToolRenderedOutput safeRenderCall(ToolDefinition definition, ToolRenderRequest request) {
        try {
            return definition == null ? null : definition.renderCall(request);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ToolRenderedOutput safeRenderResult(ToolDefinition definition, ToolRenderRequest request) {
        try {
            return definition == null ? null : definition.renderResult(request);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(argumentsJson, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String safeName(String toolName) {
        return toolName == null || toolName.isBlank() ? "<unknown>" : toolName;
    }
}
