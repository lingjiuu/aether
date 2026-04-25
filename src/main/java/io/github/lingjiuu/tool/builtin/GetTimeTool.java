package io.github.lingjiuu.tool.builtin;
import io.github.lingjiuu.tool.ToolDefinition;
import io.github.lingjiuu.tool.ToolExecutionMode;
import io.github.lingjiuu.tool.ToolExecutionContext;
import io.github.lingjiuu.tool.ToolExecutionResult;
import io.github.lingjiuu.tool.ToolRiskLevel;
import io.github.lingjiuu.tool.ToolSourceInfo;
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
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
        );
    }

    @Override
    public ToolSourceInfo sourceInfo() {
        return ToolSourceInfo.builtin();
    }

    @Override
    public ToolExecutionMode executionMode() {
        return ToolExecutionMode.PARALLEL_SAFE;
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.READ_ONLY;
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
