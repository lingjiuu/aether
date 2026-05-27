package io.github.lingjiuu.tool.permission;

public enum PermissionPreset {
    DEFAULT(ApprovalPolicy.ON_REQUEST, PermissionProfile.WORKSPACE_WRITE),
    FULL_ACCESS(ApprovalPolicy.NEVER, PermissionProfile.FULL_ACCESS);

    private final ApprovalPolicy approvalPolicy;
    private final PermissionProfile permissionProfile;

    PermissionPreset(ApprovalPolicy approvalPolicy, PermissionProfile permissionProfile) {
        this.approvalPolicy = approvalPolicy;
        this.permissionProfile = permissionProfile;
    }

    public ApprovalPolicy approvalPolicy() {
        return approvalPolicy;
    }

    public PermissionProfile permissionProfile() {
        return permissionProfile;
    }
}
