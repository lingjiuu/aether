package io.github.lingjiuu.protocol;

import java.util.List;

public record UiPermissionCatalog(
        UiPermissionMode current,
        List<UiPermissionMode> modes
) {

    public UiPermissionCatalog {
        modes = modes == null ? List.of() : List.copyOf(modes);
    }
}
