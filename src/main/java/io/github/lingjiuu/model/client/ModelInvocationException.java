package io.github.lingjiuu.model.client;

public class ModelInvocationException extends RuntimeException {

    private final ModelErrorInfo errorInfo;

    public ModelInvocationException(ModelErrorInfo errorInfo, Throwable cause) {
        super(errorInfo == null ? null : errorInfo.message(), cause);
        this.errorInfo = errorInfo == null
                ? ModelErrorInfo.of(ModelErrorCode.CONNECTION_FAILED, "Model invocation failed.")
                : errorInfo;
    }

    public ModelErrorInfo errorInfo() {
        return errorInfo;
    }
}
