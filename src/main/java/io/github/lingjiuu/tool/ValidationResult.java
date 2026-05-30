package io.github.lingjiuu.tool;

public record ValidationResult(boolean valid, String message) {

    public static ValidationResult ok() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult invalid(String message) {
        return new ValidationResult(false, message == null || message.isBlank()
                ? "Input validation failed."
                : message);
    }
}
