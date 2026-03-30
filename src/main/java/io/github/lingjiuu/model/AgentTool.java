package io.github.lingjiuu.model;

import com.openai.models.responses.FunctionTool;
import io.github.lingjiuu.tool.ToolExecutor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTool {

    private String name;

    private String description;

    private FunctionTool schema;

    private ToolExecutor executor;
}
