package io.github.lingjiuu.session.turn;

import java.util.UUID;

public record TurnId(String value) {

    public TurnId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("turn id must not be blank");
        }
    }

    public static TurnId create() {
        return new TurnId(UUID.randomUUID().toString());
    }
}
