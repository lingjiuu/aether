package io.github.lingjiuu.event;

import io.github.lingjiuu.protocol.UiEvent;

@FunctionalInterface
public interface EventSink {
    void onEvent(UiEvent event);
}
