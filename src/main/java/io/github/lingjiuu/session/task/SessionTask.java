package io.github.lingjiuu.session.task;

public interface SessionTask {

    TaskKind kind();

    void run(TaskContext context);
}
