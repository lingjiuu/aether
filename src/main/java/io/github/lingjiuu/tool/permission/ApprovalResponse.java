package io.github.lingjiuu.tool.permission;

public record ApprovalResponse(
        ApprovalId id,
        boolean approved,
        String reason
) {

    public ApprovalResponse {
        if (id == null) {
            throw new IllegalArgumentException("approval id must not be null");
        }
    }

    public static ApprovalResponse approve(ApprovalId id) {
        return new ApprovalResponse(id, true, null);
    }

    public static ApprovalResponse deny(ApprovalId id, String reason) {
        return new ApprovalResponse(id, false, reason);
    }
}
