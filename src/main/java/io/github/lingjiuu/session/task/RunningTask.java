package io.github.lingjiuu.session.task;

import io.github.lingjiuu.tool.ToolCancellationSource;

public final class RunningTask {

    private final ToolCancellationSource cancellationSource;
    private final Thread thread;

    RunningTask(ToolCancellationSource cancellationSource, Thread thread) {
        if (cancellationSource == null) {
            throw new IllegalArgumentException("cancellation source must not be null");
        }
        if (thread == null) {
            throw new IllegalArgumentException("thread must not be null");
        }
        this.cancellationSource = cancellationSource;
        this.thread = thread;
    }

    Thread thread() {
        return thread;
    }

    public void cancel() {
        cancellationSource.cancel();
        thread.interrupt();
    }
}
