package io.github.lingjiuu.message.content;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageContent implements MessageContent {

    private String data;

    private String mimeType;

    @Override
    public Type type() {
        return Type.IMAGE;
    }
}
