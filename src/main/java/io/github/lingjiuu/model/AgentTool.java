package io.github.lingjiuu.model;

import com.openai.models.responses.FunctionTool;
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

    private String label;

    private String description;

    private FunctionTool schema;
}
