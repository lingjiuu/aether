package io.github.lingjiuu.agent.task;

public interface SessionTask {

    TaskKind kind();

    void run(TaskContext context);
}
