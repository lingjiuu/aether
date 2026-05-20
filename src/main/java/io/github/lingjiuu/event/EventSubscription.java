package io.github.lingjiuu.event;

@FunctionalInterface
public interface EventSubscription extends AutoCloseable {

    @Override
    void close();
}
