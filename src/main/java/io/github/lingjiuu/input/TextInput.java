package io.github.lingjiuu.input;

public record TextInput(String text) implements InputItem {

    public TextInput {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text input must not be blank");
        }
    }

    @Override
    public Kind kind() {
        return Kind.TEXT;
    }
}
