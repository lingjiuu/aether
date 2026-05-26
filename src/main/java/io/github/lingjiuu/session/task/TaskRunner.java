package io.github.lingjiuu.session.task;

import io.github.lingjiuu.tool.ToolCancellationSource;

public class TaskRunner {

    private volatile RunningTask runningTask;

    public synchronized RunningTask start(
            ToolCancellationSource cancellationSource,
            String threadName,
            Runnable body
    ) {
        if (runningTask != null) {
            throw new IllegalStateException("A session task is already running.");
        }
        if (cancellationSource == null) {
            throw new IllegalArgumentException("cancellation source must not be null");
        }
        if (body == null) {
            throw new IllegalArgumentException("task body must not be null");
        }

        Thread thread = Thread.ofVirtual()
                .name(threadName == null || threadName.isBlank() ? "aether-task" : threadName)
                .unstarted(() -> {
                    try {
                        body.run();
                    } catch (Throwable t) {
                        if (!cancellationSource.token().isCancellationRequested()) {
                            throw t;
                        }
                    } finally {
                        clearIfCurrent(Thread.currentThread());
                    }
                });
        RunningTask running = new RunningTask(cancellationSource, thread);
        runningTask = running;
        thread.start();
        return running;
    }

    public boolean cancelRunningTask() {
        RunningTask running = runningTask;
        if (running == null) {
            return false;
        }
        running.cancel();
        return true;
    }

    private synchronized void clearIfCurrent(Thread thread) {
        RunningTask running = runningTask;
        if (running != null && running.thread() == thread) {
            runningTask = null;
        }
    }
}
