package io.github.lingjiuu.event;

import io.github.lingjiuu.protocol.UiEvent;
import io.github.lingjiuu.transcript.TranscriptRecorder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class EventManager implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(EventManager.class.getName());

    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();
    private final List<UiEvent> timeline;
    private final TranscriptRecorder transcriptRecorder;
    private final AtomicLong sequence;
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "aether-ui-events");
        thread.setDaemon(true);
        return thread;
    });

    public EventManager() {
        this(null, List.of(), 0);
    }

    public EventManager(
            TranscriptRecorder transcriptRecorder,
            List<UiEvent> initialTimeline,
            long initialSequence
    ) {
        this.transcriptRecorder = transcriptRecorder;
        List<UiEvent> seedTimeline = initialTimeline == null ? List.of() : initialTimeline;
        this.timeline = new CopyOnWriteArrayList<>(seedTimeline);
        this.sequence = new AtomicLong(Math.max(initialSequence, maxSequence(seedTimeline)));
    }

    public EventSubscription subscribe(EventSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        sinks.add(sink);
        return () -> sinks.remove(sink);
    }

    public List<UiEvent> timelineEvents() {
        return List.copyOf(timeline);
    }

    public List<UiEvent> eventsAfter(long sequence) {
        return timeline.stream()
                .filter(event -> event != null
                        && event.getSequence() != null
                        && event.getSequence() > sequence)
                .toList();
    }

    public void replayTimeline(EventSink sink) {
        if (sink == null) {
            return;
        }
        for (UiEvent event : timelineEvents()) {
            try {
                sink.onEvent(event);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to replay UI event to sink " + sink, e);
            }
        }
    }

    public void emit(UiEvent event) {
        if (event == null) {
            return;
        }
        try {
            dispatcher.execute(() -> {
                event.stamp(sequence.incrementAndGet(), System.currentTimeMillis());
                persist(event);
                timeline.add(event);
                dispatch(event);
            });
        } catch (RejectedExecutionException e) {
            LOGGER.log(Level.FINE, "UI event queue is closed; dropping event " + event.getType(), e);
        }
    }

    public void flush() {
        try {
            Future<?> future = dispatcher.submit(() -> {
            });
            future.get();
        } catch (RejectedExecutionException e) {
            LOGGER.log(Level.FINE, "UI event queue is closed; skipping flush.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to flush UI event queue.", e);
        }
    }

    @Override
    public void close() {
        flush();
        dispatcher.shutdownNow();
    }

    private void dispatch(UiEvent event) {
        for (EventSink sink : sinks) {
            try {
                sink.onEvent(event);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to dispatch UI event to sink " + sink, e);
            }
        }
    }

    private void persist(UiEvent event) {
        if (transcriptRecorder == null || !shouldPersist(event)) {
            return;
        }
        try {
            transcriptRecorder.recordEvent(event);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to persist UI event " + event.getType(), e);
        }
    }

    static boolean shouldPersist(UiEvent event) {
        if (event == null || event.getType() == null) {
            return false;
        }
        return switch (event.getType()) {
            case TURN_STARTED,
                    TURN_COMPLETED,
                    TURN_ABORTED,
                    SESSION_NAME_UPDATED,
                    USER_MESSAGE,
                    CONTEXT_MESSAGE,
                    ITEM_COMPLETED,
                    TOOL_RESULT,
                    TOKEN_USAGE,
                    COMPACT_FINISHED,
                    COMPACT_SKIPPED,
                    SESSION_RESET,
                    SKILLS_CHANGED,
                    ERROR -> true;
            case ITEM_STARTED,
                    ASSISTANT_TEXT_DELTA,
                    REASONING_DELTA,
                    TOOL_CALL_ARGUMENTS_DELTA,
                    TOOL_CALL_ARGUMENTS_DONE,
                    TOOL_CALL,
                    TOOL_EXECUTION_BEGIN,
                    TOOL_EXECUTION_UPDATE,
                    TOOL_EXECUTION_END,
                    APPROVAL_REQUESTED,
                    APPROVAL_RESOLVED,
                    COMPACT_STARTED,
                    MODEL_CHANGED -> false;
        };
    }

    private long maxSequence(List<UiEvent> events) {
        long max = 0;
        for (UiEvent event : events) {
            if (event != null && event.getSequence() != null) {
                max = Math.max(max, event.getSequence());
            }
        }
        return max;
    }
}
