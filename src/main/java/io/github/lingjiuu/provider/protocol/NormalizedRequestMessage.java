package io.github.lingjiuu.provider.protocol;

import java.util.List;

public interface NormalizedRequestMessage {

    Kind kind();

    List<NormalizedContent> contents();

    enum Kind {
        USER,
        ASSISTANT,
        CONTEXT
    }
}
