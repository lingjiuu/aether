package io.github.lingjiuu.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventManager {

    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();

    public EventSubscription subscribe(EventSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        sinks.add(sink);
        return () -> sinks.remove(sink);
    }

    public void emit(UiEvent event) {
        if (event == null) {
            return;
        }
        for (EventSink sink : sinks) {
            try {
                sink.onEvent(event);
            } catch (RuntimeException e) {
                throw new EventDispatchException("Failed to dispatch UI event.", e);
            }
        }
    }
}
