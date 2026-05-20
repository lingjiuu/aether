package io.github.lingjiuu.event;

@FunctionalInterface
public interface EventSink {
    void onEvent(UiEvent event);
}
