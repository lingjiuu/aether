package io.github.lingjiuu.infra.config;

public class AetherConfigException extends RuntimeException {

    public AetherConfigException(String message) {
        super(message);
    }

    public AetherConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
