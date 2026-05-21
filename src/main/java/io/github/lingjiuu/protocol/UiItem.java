package io.github.lingjiuu.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UiItem {

    private String itemId;

    private UiItemKind kind;

    private Integer contentIndex;

    private UiItemBody body;
}
