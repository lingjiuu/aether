package io.github.lingjiuu.tool.result;

import io.github.lingjiuu.message.content.MessageContent;
import io.github.lingjiuu.message.content.TextContent;

import java.util.List;

public record ModelToolResult(List<MessageContent> contents, boolean error) {

    public ModelToolResult {
        contents = contents == null ? List.of() : List.copyOf(contents);
    }

    public static ModelToolResult text(String text) {
        return new ModelToolResult(textContents(text), false);
    }

    public static ModelToolResult errorText(String text) {
        return new ModelToolResult(textContents(text), true);
    }

    private static List<MessageContent> textContents(String text) {
        return List.of(TextContent.builder()
                .text(text == null ? "" : text)
                .build());
    }
}
