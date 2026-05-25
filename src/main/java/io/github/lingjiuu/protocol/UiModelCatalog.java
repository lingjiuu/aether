package io.github.lingjiuu.protocol;

import java.util.List;

public record UiModelCatalog(
        UiModelSelection current,
        List<UiModelInfo> models,
        List<String> reasoningEfforts
) {

    public UiModelCatalog {
        models = models == null ? List.of() : List.copyOf(models);
        reasoningEfforts = reasoningEfforts == null ? List.of() : List.copyOf(reasoningEfforts);
    }
}
