package io.github.lingjiuu.model.client;

public record ModelErrorInfo(
        ModelErrorCode code,
        String message,
        Integer httpStatusCode,
        Long retryAfterMillis
) {

    public ModelErrorInfo {
        code = code == null ? ModelErrorCode.INVALID_REQUEST : code;
        message = message == null ? "" : message;
    }

    public static ModelErrorInfo of(ModelErrorCode code, String message) {
        return new ModelErrorInfo(code, message, null, null);
    }

    public static ModelErrorInfo fromOpenAiException(Throwable error) {
        return new ModelErrorInfo(
                ModelErrorCode.fromOpenAiException(error),
                errorMessage(error),
                ModelErrorCode.httpStatusCode(error),
                ModelErrorCode.retryAfterMillis(error)
        );
    }

    public boolean retryableAsRequestFailure() {
        return code.retryableAsRequestFailure(retryAfterMillis);
    }

    public boolean retryableAsStreamFailure() {
        return code.retryableAsStreamFailure(retryAfterMillis);
    }

    public boolean contextWindowExceeded() {
        return code.contextWindowExceeded();
    }

    private static String errorMessage(Throwable error) {
        if (error == null) {
            return "unknown model error";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
