package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.tool.ToolCancellationSource;

public record RunningTask(SessionTask task, ToolCancellationSource cancellationSource) {

    public void abort() {
        cancellationSource.cancel();
    }
}
