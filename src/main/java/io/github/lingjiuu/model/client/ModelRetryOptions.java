package io.github.lingjiuu.model.client;

import java.util.concurrent.ThreadLocalRandom;

public record ModelRetryOptions(
        int requestMaxRetries,
        int streamMaxRetries,
        long initialDelayMillis,
        long maxDelayMillis,
        double jitterRatio
) {

    public static final int DEFAULT_REQUEST_MAX_RETRIES = 4;
    public static final int DEFAULT_STREAM_MAX_RETRIES = 5;
    public static final long DEFAULT_INITIAL_DELAY_MILLIS = 200L;
    public static final long DEFAULT_MAX_DELAY_MILLIS = 10_000L;
    public static final double DEFAULT_JITTER_RATIO = 0.1d;

    private static final int MAX_RETRIES = 100;

    public ModelRetryOptions {
        requestMaxRetries = clamp(requestMaxRetries, 0, MAX_RETRIES);
        streamMaxRetries = clamp(streamMaxRetries, 0, MAX_RETRIES);
        initialDelayMillis = Math.max(1L, initialDelayMillis);
        maxDelayMillis = Math.max(initialDelayMillis, maxDelayMillis);
        jitterRatio = Math.max(0d, Math.min(jitterRatio, 1d));
    }

    public static ModelRetryOptions defaults() {
        return new ModelRetryOptions(
                DEFAULT_REQUEST_MAX_RETRIES,
                DEFAULT_STREAM_MAX_RETRIES,
                DEFAULT_INITIAL_DELAY_MILLIS,
                DEFAULT_MAX_DELAY_MILLIS,
                DEFAULT_JITTER_RATIO
        );
    }

    public static ModelRetryOptions fromOverrides(
            Integer requestMaxRetries,
            Integer streamMaxRetries,
            Long initialDelayMillis,
            Long maxDelayMillis,
            Double jitterRatio
    ) {
        ModelRetryOptions defaults = defaults();
        return new ModelRetryOptions(
                requestMaxRetries == null ? defaults.requestMaxRetries() : requestMaxRetries,
                streamMaxRetries == null ? defaults.streamMaxRetries() : streamMaxRetries,
                initialDelayMillis == null ? defaults.initialDelayMillis() : initialDelayMillis,
                maxDelayMillis == null ? defaults.maxDelayMillis() : maxDelayMillis,
                jitterRatio == null ? defaults.jitterRatio() : jitterRatio
        );
    }

    public long delayMillis(int attempt) {
        if (attempt <= 0) {
            return initialDelayMillis;
        }
        int exponent = Math.min(attempt - 1, 30);
        long multiplier = 1L << exponent;
        long base = saturatingMultiply(initialDelayMillis, multiplier);
        long capped = Math.min(base, maxDelayMillis);
        if (jitterRatio <= 0d) {
            return capped;
        }
        double jitter = ThreadLocalRandom.current().nextDouble(1d - jitterRatio, 1d + jitterRatio);
        return Math.max(1L, (long) (capped * jitter));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static long saturatingMultiply(long value, long multiplier) {
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
