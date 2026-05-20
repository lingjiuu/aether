package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.tool.ToolCancellationSource;

public class TaskRunner {

    private volatile RunningTask runningTask;

    public synchronized void run(SessionTask task, TaskContext context, ToolCancellationSource cancellationSource) {
        if (runningTask != null) {
            throw new IllegalStateException("A session task is already running.");
        }
        RunningTask running = new RunningTask(task, cancellationSource);
        runningTask = running;
        try {
            task.run(context);
        } finally {
            if (runningTask == running) {
                runningTask = null;
            }
        }
    }

    public void abort() {
        RunningTask running = runningTask;
        if (running != null) {
            running.abort();
        }
    }

    public boolean isRunning() {
        return runningTask != null;
    }
}
