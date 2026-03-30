package io.github.lingjiuu.message.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TextContent implements MessageContent {

    private String text;

    private String textSignature;

    @Override
    public Type type() {
        return Type.TEXT;
    }
}
