package io.github.lingjiuu.tool;

public interface ToolCancellationToken {

    boolean isCancellationRequested();

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new ToolCancelledException();
        }
    }

    AutoCloseable onCancel(Runnable callback);

    static ToolCancellationToken none() {
        return NoopToolCancellationToken.INSTANCE;
    }

    final class NoopToolCancellationToken implements ToolCancellationToken {
        private static final NoopToolCancellationToken INSTANCE = new NoopToolCancellationToken();

        private NoopToolCancellationToken() {
        }

        @Override
        public boolean isCancellationRequested() {
            return false;
        }

        @Override
        public AutoCloseable onCancel(Runnable callback) {
            return () -> {
            };
        }
    }
}
