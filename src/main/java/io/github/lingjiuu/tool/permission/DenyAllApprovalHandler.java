package io.github.lingjiuu.tool.permission;

public class DenyAllApprovalHandler implements ApprovalHandler {

    @Override
    public ApprovalResponse requestApproval(ApprovalRequest request) {
        if (request == null) {
            return null;
        }
        return ApprovalResponse.deny(request.id(), "No approval handler is configured.");
    }
}
