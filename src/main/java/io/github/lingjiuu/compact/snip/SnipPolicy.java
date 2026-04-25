package io.github.lingjiuu.compact.snip;

public record SnipPolicy(
        long maxActiveTokens,
        int keepRecentMessages,
        int minimumRemovedMessages
) {

    public SnipPolicy {
        if (maxActiveTokens <= 0) {
            throw new IllegalArgumentException("maxActiveTokens must be positive");
        }
        if (keepRecentMessages <= 0) {
            throw new IllegalArgumentException("keepRecentMessages must be positive");
        }
        if (minimumRemovedMessages <= 0) {
            throw new IllegalArgumentException("minimumRemovedMessages must be positive");
        }
    }

    public static SnipPolicy defaultPolicy() {
        return new SnipPolicy(30_000, 12, 2);
    }
}
