package io.github.lingjiuu.tool.builtin;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolUpdateCallback;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public class GetTimeTool implements ToolDefinition {

    @Override
    public String name() {
        return "get_time";
    }

    @Override
    public String label() {
        return "get_time";
    }

    @Override
    public String description() {
        return "Get the current time in Asia/Shanghai.";
    }

    @Override
    public FunctionTool schema() {
        return FunctionTool.builder()
                .name(name())
                .description(description())
                .strict(true)
                .parameters(FunctionTool.Parameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties", JsonValue.from(Map.of()))
                        .putAdditionalProperty("required", JsonValue.from(List.of()))
                        .build())
                .build();
    }

    @Override
    public String promptSnippet() {
        return "Get the current time";
    }

    @Override
    public List<String> promptGuidelines() {
        return List.of("Use get_time when the user asks for the current time or date.");
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, ToolUpdateCallback onUpdate) {
        ZonedDateTime now = ZonedDateTime.now();
        if (onUpdate != null) {
            onUpdate.onUpdate(ToolExecutionResult.text("Fetching current Shanghai time..."));
        }
        return ToolExecutionResult.builder()
                .contents(ToolExecutionResult.text(now.toString()).getContents())
                .details(Map.of(
                        "timezone", now.getZone().getId(),
                        "isoTime", now.toString()
                ))
                .error(false)
                .build();
    }
}
