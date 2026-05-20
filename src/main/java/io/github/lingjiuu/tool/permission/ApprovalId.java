package io.github.lingjiuu.tool.permission;

import java.util.UUID;

public record ApprovalId(String value) {

    public ApprovalId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("approval id must not be blank");
        }
    }

    public static ApprovalId create() {
        return new ApprovalId(UUID.randomUUID().toString());
    }
}
