package io.github.lingjiuu.compact.snip;

import java.util.List;

public record SnipBoundaryMetadata(
        List<String> removedMessageIds,
        long tokensFreed
) {

    public SnipBoundaryMetadata {
        removedMessageIds = removedMessageIds == null ? List.of() : List.copyOf(removedMessageIds);
    }
}
