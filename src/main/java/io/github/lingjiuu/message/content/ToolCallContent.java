package io.github.lingjiuu.message.content;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallContent implements MessageContent {

    private String toolCallId;
    private String toolName;
    private String argumentsJson;
    private JsonNode arguments;

    @Override
    public Type type() {
        return Type.TOOLCALL;
    }

}
