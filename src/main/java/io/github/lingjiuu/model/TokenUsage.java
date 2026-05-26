package io.github.lingjiuu.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record TokenUsage(
        long inputTokens,
        long cachedInputTokens,
        long outputTokens,
        long reasoningOutputTokens,
        long totalTokens
) {

    public static TokenUsage empty() {
        return new TokenUsage(0, 0, 0, 0, 0);
    }

    public static TokenUsage fromUsageMap(Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) {
            return empty();
        }

        long inputTokens = numberValue(usage, "input_tokens", "inputTokens");
        long outputTokens = numberValue(usage, "output_tokens", "outputTokens");
        long totalTokens = numberValue(usage, "total_tokens", "totalTokens");

        Map<String, Object> inputDetails = mapValue(usage, "input_tokens_details", "inputTokensDetails");
        long cachedInputTokens = numberValue(usage, "cached_input_tokens", "cachedInputTokens");
        if (cachedInputTokens == 0 && inputDetails != null) {
            cachedInputTokens = numberValue(inputDetails, "cached_tokens", "cachedTokens");
        }

        Map<String, Object> outputDetails = mapValue(usage, "output_tokens_details", "outputTokensDetails");
        long reasoningOutputTokens = numberValue(usage, "reasoning_output_tokens", "reasoningOutputTokens");
        if (reasoningOutputTokens == 0 && outputDetails != null) {
            reasoningOutputTokens = numberValue(outputDetails, "reasoning_tokens", "reasoningTokens");
        }

        if (totalTokens == 0) {
            totalTokens = inputTokens + outputTokens;
        }
        return new TokenUsage(inputTokens, cachedInputTokens, outputTokens, reasoningOutputTokens, totalTokens);
    }

    public TokenUsage add(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
                inputTokens + other.inputTokens,
                cachedInputTokens + other.cachedInputTokens,
                outputTokens + other.outputTokens,
                reasoningOutputTokens + other.reasoningOutputTokens,
                totalTokens + other.totalTokens
        );
    }

    public boolean isEmpty() {
        return inputTokens == 0
                && cachedInputTokens == 0
                && outputTokens == 0
                && reasoningOutputTokens == 0
                && totalTokens == 0;
    }

    private static long numberValue(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Long.parseLong(stringValue.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static Map<String, Object> mapValue(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        if (!(value instanceof Map<?, ?> rawMap)) {
            return null;
        }

        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() != null) {
                copied.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return copied;
    }

    private static Object value(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }
}
