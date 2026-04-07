package io.github.lingjiuu.tool;

import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {

    @Builder.Default
    private List<MessageContent> contents = new ArrayList<>();

    private Object details;

    private boolean error;

    public static ToolExecutionResult text(String text) {
        return ToolExecutionResult.builder()
                .contents(List.of(
                        TextContent.builder()
                                .text(text)
                                .build()
                ))
                .error(false)
                .build();
    }

    public static ToolExecutionResult errorText(String text) {
        return ToolExecutionResult.builder()
                .contents(List.of(
                        TextContent.builder()
                                .text(text)
                                .build()
                ))
                .error(true)
                .build();
    }
}
