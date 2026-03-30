package io.github.lingjiuu.tool;

import com.openai.models.responses.Tool;
import io.github.lingjiuu.message.ToolResultMessage;
import io.github.lingjiuu.message.content.TextContent;
import io.github.lingjiuu.message.content.ToolCallContent;
import io.github.lingjiuu.model.AgentTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    private final Map<String, AgentTool> toolsByName = new LinkedHashMap<>();

    public void register(AgentTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        if (tool.getName() == null || tool.getName().isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        toolsByName.put(tool.getName(), tool);
    }

    public int size() {
        return toolsByName.size();
    }

    public List<Tool> toOpenAiTools() {
        List<Tool> tools = new ArrayList<>();
        for (AgentTool tool : toolsByName.values()) {
            tools.add(Tool.ofFunction(tool.getSchema()));
        }
        return tools;
    }

    public ToolResultMessage execute(ToolCallContent toolCall) {
        AgentTool tool = require(toolCall.getToolName());
        String output = tool.getExecutor().execute(toolCall.getArgumentsJson());
        return ToolResultMessage.builder()
                .toolCallId(toolCall.getToolCallId())
                .toolName(toolCall.getToolName())
                .isError(false)
                .contents(List.of(
                        TextContent.builder()
                                .text(output)
                                .build()
                ))
                .build();
    }

    private AgentTool require(String name) {
        AgentTool tool = toolsByName.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unsupported tool: " + name);
        }
        return tool;
    }
}
