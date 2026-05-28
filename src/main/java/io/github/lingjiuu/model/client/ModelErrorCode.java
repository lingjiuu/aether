package io.github.lingjiuu.model.client;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.SseException;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

public enum ModelErrorCode {
    STREAM_DISCONNECTED,
    CONNECTION_FAILED,
    REQUEST_TIMEOUT,
    HTTP_5XX,
    RATE_LIMIT,
    CONTEXT_WINDOW_EXCEEDED,
    AUTH,
    INVALID_REQUEST;

    public static ModelErrorCode fromOpenAiException(Throwable error) {
        OpenAIServiceException serviceException = findCause(error, OpenAIServiceException.class);
        if (serviceException != null) {
            ModelErrorCode code = fromMessage(serviceException.getMessage(), null);
            if (code != null) {
                return code;
            }
            return fromHttpStatus(serviceException.statusCode());
        }
        if (findCause(error, SseException.class) != null) {
            return STREAM_DISCONNECTED;
        }
        if (findCause(error, OpenAIRetryableException.class) != null
                || findCause(error, OpenAIIoException.class) != null) {
            return CONNECTION_FAILED;
        }
        if (findCause(error, OpenAIException.class) != null) {
            ModelErrorCode code = fromMessage(errorMessage(error), null);
            return code == null ? CONNECTION_FAILED : code;
        }
        ModelErrorCode code = fromMessage(errorMessage(error), null);
        return code == null ? CONNECTION_FAILED : code;
    }

    public static ModelErrorCode fromHttpStatus(int statusCode) {
        if (statusCode == 408) {
            return REQUEST_TIMEOUT;
        }
        if (statusCode == 429) {
            return RATE_LIMIT;
        }
        if (statusCode == 401 || statusCode == 403) {
            return AUTH;
        }
        if (statusCode >= 500) {
            return HTTP_5XX;
        }
        return INVALID_REQUEST;
    }

    public static ModelErrorCode fromResponseError(String code, String message) {
        ModelErrorCode fromMessage = fromMessage(message, null);
        if (fromMessage != null) {
            return fromMessage;
        }
        if (code == null || code.isBlank()) {
            return INVALID_REQUEST;
        }
        return switch (code.trim().toLowerCase(Locale.ROOT)) {
            case "context_window_exceeded", "context_length_exceeded" -> CONTEXT_WINDOW_EXCEEDED;
            case "rate_limit_exceeded" -> RATE_LIMIT;
            case "server_error", "internal_server_error" -> HTTP_5XX;
            case "invalid_api_key", "authentication_error", "permission_denied" -> AUTH;
            default -> INVALID_REQUEST;
        };
    }

    public static ModelErrorCode fromIncompleteReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return INVALID_REQUEST;
        }
        return switch (reason.trim().toLowerCase(Locale.ROOT)) {
            case "max_output_tokens", "content_filter" -> INVALID_REQUEST;
            case "context_window_exceeded", "context_length_exceeded" -> CONTEXT_WINDOW_EXCEEDED;
            default -> INVALID_REQUEST;
        };
    }

    public static ModelErrorCode fromMessage(String message, ModelErrorCode fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("stream disconnected before completion:")
                || normalized.contains("stream closed before response.completed")
                || normalized.contains("stream ended unexpectedly")
                || normalized.contains("sse")) {
            return STREAM_DISCONNECTED;
        }
        if (normalized.contains("context_window_exceeded")
                || normalized.contains("context length")
                || normalized.contains("context window")
                || normalized.contains("maximum context")
                || normalized.contains("too many tokens")
                || normalized.contains("token limit")) {
            return CONTEXT_WINDOW_EXCEEDED;
        }
        if (normalized.contains("rate limit") || normalized.contains("429")) {
            return RATE_LIMIT;
        }
        if (normalized.contains("timeout") || normalized.contains("timed out")) {
            return REQUEST_TIMEOUT;
        }
        if (normalized.contains("401")
                || normalized.contains("403")
                || normalized.contains("unauthorized")
                || normalized.contains("permission denied")
                || normalized.contains("invalid api key")) {
            return AUTH;
        }
        if (normalized.contains("500")
                || normalized.contains("502")
                || normalized.contains("503")
                || normalized.contains("504")
                || normalized.contains("internal server")
                || normalized.contains("server error")) {
            return HTTP_5XX;
        }
        if (normalized.contains("connection")
                || normalized.contains("connect")
                || normalized.contains("network")
                || normalized.contains("broken pipe")) {
            return CONNECTION_FAILED;
        }
        return fallback;
    }

    public static Integer httpStatusCode(Throwable error) {
        OpenAIServiceException serviceException = findCause(error, OpenAIServiceException.class);
        return serviceException == null ? null : serviceException.statusCode();
    }

    public static Long retryAfterMillis(Throwable error) {
        OpenAIServiceException serviceException = findCause(error, OpenAIServiceException.class);
        if (serviceException == null || serviceException.headers() == null) {
            return null;
        }
        List<String> values = serviceException.headers().values("retry-after");
        if (values == null || values.isEmpty()) {
            return null;
        }
        return parseRetryAfterMillis(values.getFirst());
    }

    public boolean retryableAsRequestFailure(Long retryAfterMillis) {
        return switch (this) {
            case CONNECTION_FAILED, REQUEST_TIMEOUT, HTTP_5XX -> true;
            case RATE_LIMIT -> retryAfterMillis != null && retryAfterMillis >= 0;
            default -> false;
        };
    }

    public boolean retryableAsStreamFailure(Long retryAfterMillis) {
        return switch (this) {
            case STREAM_DISCONNECTED -> true;
            case RATE_LIMIT -> retryAfterMillis != null && retryAfterMillis >= 0;
            default -> false;
        };
    }

    public boolean contextWindowExceeded() {
        return this == CONTEXT_WINDOW_EXCEEDED;
    }

    private static Long parseRetryAfterMillis(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return Math.max(0L, seconds) * 1_000L;
        } catch (NumberFormatException ignored) {
            try {
                long millis = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .toEpochMilli() - System.currentTimeMillis();
                return Math.max(0L, millis);
            } catch (DateTimeParseException ignoredDate) {
                try {
                    long millis = Instant.parse(trimmed).toEpochMilli() - System.currentTimeMillis();
                    return Math.max(0L, millis);
                } catch (DateTimeParseException ignoredInstant) {
                    return null;
                }
            }
        }
    }

    private static String errorMessage(Throwable error) {
        return error == null ? null : error.getMessage();
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
