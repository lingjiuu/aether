package io.github.lingjiuu.model.client;

import io.github.lingjiuu.message.Message;
import io.github.lingjiuu.model.ReasoningOptions;
import io.github.lingjiuu.tool.Tool;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ModelRequest {

    public static ModelRequest from(
            String baseInstructions,
            ReasoningOptions reasoning,
            List<Message> messages,
            List<Tool> tools
    ) {
        return ModelRequest.builder()
                .baseInstructions(baseInstructions)
                .tools(tools == null ? List.of() : List.copyOf(tools))
                .messages(messages == null ? List.of() : List.copyOf(messages))
                .callOptions(ModelCallOptions.builder()
                        .reasoning(reasoning)
                        .build())
                .build();
    }

    private String baseInstructions;

    @Builder.Default
    private List<Tool> tools = new ArrayList<>();

    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    private ModelCallOptions callOptions;
}
