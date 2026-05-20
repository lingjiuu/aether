package io.github.lingjiuu.tool;


import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ToolCancellationSource {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();
    private final ToolCancellationToken token = new SourceToken();

    public ToolCancellationToken token() {
        return token;
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        for (Runnable callback : callbacks) {
            runCallback(callback);
        }
        callbacks.clear();
    }

    private void runCallback(Runnable callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Cancellation must notify every registered callback even when one fails.
        }
    }

    private final class SourceToken implements ToolCancellationToken {
        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }

        @Override
        public AutoCloseable onCancel(Runnable callback) {
            if (callback == null) {
                return () -> {
                };
            }
            if (cancelled.get()) {
                runCallback(callback);
                return () -> {
                };
            }
            callbacks.add(callback);
            if (cancelled.get() && callbacks.remove(callback)) {
                runCallback(callback);
            }
            return () -> callbacks.remove(callback);
        }
    }
}
