package io.github.lingjiuu.agent.task;

import io.github.lingjiuu.tool.ToolCancellationSource;
import junit.framework.TestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class TaskRunnerTest extends TestCase {

    public void testCancelledTaskSuppressesEscapedThrowable() throws Exception {
        TaskRunner runner = new TaskRunner();
        ToolCancellationSource cancellationSource = new ToolCancellationSource();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if ("aether-test-cancel".equals(thread.getName())) {
                uncaught.set(throwable);
            }
        });

        try {
            RunningTask task = runner.start(cancellationSource, "aether-test-cancel", () -> {
                started.countDown();
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(5));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });

            assertTrue(started.await(1, TimeUnit.SECONDS));
            task.cancel();
            task.thread().join(TimeUnit.SECONDS.toMillis(1));

            assertNull(uncaught.get());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        }
    }

    public void testNonCancelledTaskStillReportsEscapedThrowable() throws Exception {
        TaskRunner runner = new TaskRunner();
        ToolCancellationSource cancellationSource = new ToolCancellationSource();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if ("aether-test-fail".equals(thread.getName())) {
                uncaught.set(throwable);
            }
        });

        try {
            RunningTask task = runner.start(cancellationSource, "aether-test-fail", () -> {
                throw new IllegalStateException("boom");
            });
            task.thread().join(TimeUnit.SECONDS.toMillis(1));

            assertTrue(uncaught.get() instanceof IllegalStateException);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        }
    }
}
